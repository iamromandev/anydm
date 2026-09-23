"""The claim loop.

One coroutine per worker, all sharing a ``DownloadControl`` and pulling from
Postgres. The database arbitrates who gets which row; everything else here is
sequencing and bookkeeping.
"""

from __future__ import annotations

import asyncio
import shutil
import uuid
from datetime import timedelta
from pathlib import Path
from typing import Any

import httpx
from loguru import logger

from src.core.common import now
from src.core.error import Error
from src.data.db.model import Task
from src.data.repo.download.interface import SegmentRepo, TaskRepo
from src.data.schema.download import TaskSchema
from src.data.type import Platform, TaskStatus
from src.lib.event import EventHub
from src.lib.youtube import YouTubeClient
from src.service.download import retry as retry_policy
from src.service.download.control import DownloadControl
from src.service.download.disk import DiskGuard, is_insufficient_storage, storage_error
from src.service.download.downloader import Stopped
from src.service.download.paths import final_path, part_path, task_dir
from src.service.download.post_process import PostProcessor
from src.service.download.progress import AggregateSample
from src.service.download.segment import Segment
from src.service.download.segmented import SegmentedDownloader
from src.service.download.url_source import UrlProvider, UrlSource

_IDLE_POLL_SECONDS = 5.0
#: How long a task waits before checking the disk again.
_SPACE_RECHECK_SECONDS = 30


class DownloadWorker:
    def __init__(
        self,
        *,
        name: str,
        repo: TaskRepo,
        segment_repo: SegmentRepo,
        client: YouTubeClient,
        engine: SegmentedDownloader,
        post_processor: PostProcessor,
        control: DownloadControl,
        hub: EventHub,
        downloads_root: Path,
        max_attempts: int,
        segments: int,
        disk: DiskGuard | None = None,
    ) -> None:
        self._disk = disk
        self._name = name
        self._repo = repo
        self._segment_repo = segment_repo
        self._client = client
        self._engine = engine
        self._post_processor = post_processor
        self._control = control
        self._hub = hub
        self._root = downloads_root
        self._max_attempts = max_attempts
        self._segments = segments

    async def run_forever(self) -> None:
        while True:
            try:
                task = await self._repo.claim_next()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.error("{}|claim failed: {}", self._name, exc)
                await asyncio.sleep(_IDLE_POLL_SECONDS)
                continue

            if task is None:
                await self._control.wait_for_work(timeout=_IDLE_POLL_SECONDS)
                continue

            await self.run_task(task)

    async def run_task(self, task: Task) -> None:
        # Checked before the attempt is counted: a task that never started has
        # not used any of its retries.
        if self._disk is not None:
            try:
                self._disk.require()
            except Error as error:
                await self._wait_for_space(task, error)
                return

        logger.info("{}|starting {} ({})", self._name, task.id, task.filename)
        task.attempts += 1
        # ``update_fields`` throughout this class, not decoration: ``task`` was
        # loaded at claim time and ``flush_progress`` writes straight to the row,
        # so a bare ``save()`` would push this stale copy's zeroes back over
        # every byte count the download has since recorded.
        await task.save(update_fields=["attempts"])

        try:
            parts = await self._download_parts(task)
            destination = final_path(self._root, task.id, task.filename)
            await self._post_processor.run(task, parts, destination)
            await self._mark_complete(task, destination)
        except Stopped:
            # A pause or a cancel already set the row's status, so it is not
            # this worker's to change. But cancel deleted the task directory
            # while this download still held the file open, and the next
            # ``mkdir``/``open`` recreated it — so a cancelled task must have
            # its files swept a second time, once the writer has let go.
            logger.info("{}|stopped {}", self._name, task.id)
            self._control.clear_stop(task.id)
            await task.refresh_from_db()
            if task.status == TaskStatus.CANCELED:
                remove_task_files(self._root, task.id)
            self._emit(task)
        except Error as error:
            if is_insufficient_storage(error):
                await self._wait_for_space(task, error, refund=True)
            else:
                await self._mark_failed(task, error)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            # A full disk surfaces from the writer as a bare ``OSError``. It is
            # the one write error worth waiting out rather than failing on.
            storage = storage_error(exc) if isinstance(exc, OSError) else None
            if storage is not None:
                await self._wait_for_space(task, storage, refund=True)
                return
            logger.exception("{}|unexpected failure on {}", self._name, task.id)
            await self._mark_failed(task, Error.internal(message=str(exc)))

    def _emit(self, task: Task) -> None:
        self._hub.publish("task", TaskSchema.model_validate(task).to_json())

    async def _download_parts(self, task: Task) -> dict[str, Path]:
        """Fetch every stream the plan names, resuming any ``.part`` already there."""
        if task.platform == Platform.DIRECT:
            destination = part_path(self._root, task.id, "file")
            source_url = task.source_url

            async def direct() -> str:
                return source_url

            await self._fetch(task, "file", direct, destination, offset=0)
            return {"file": destination}

        wanted: list[tuple[str, int]] = []
        if task.video_itag is not None:
            wanted.append(("video", task.video_itag))
        if task.audio_itag is not None:
            wanted.append(("audio", task.audio_itag))
        if not wanted:
            raise Error.internal(message="Task names no stream to download")

        parts: dict[str, Path] = {}
        # Bytes already on disk from earlier parts. Without this the second part
        # would restart the percentage at zero and the UI would run backwards.
        offset = 0
        video_id = task.video_id or ""
        for name, itag in wanted:
            destination = part_path(self._root, task.id, name)

            # Always re-resolved: these URLs expire within hours and bind to the
            # requesting IP, so a stored one is worthless on a resume. Bound as
            # defaults because the loop variables would otherwise be read at call
            # time, and every part would resolve the last itag.
            async def resolve(itag: int = itag, video_id: str = video_id) -> str:
                return await self._client.stream_url(video_id, itag)

            await self._fetch(task, name, resolve, destination, offset=offset)
            parts[name] = destination
            offset += destination.stat().st_size
        return parts

    def _segment_count(self, attempts: int, downloaded: int) -> int:
        """Halve the connections on every retry: 4, then 2, then 1.

        Some servers 429 under four connections and are perfectly happy with
        one. ``attempts`` already counts, so this needs no new column, and it
        turns a hard failure on a strict server into a slower success.

        ``downloaded`` outranks all of that. A part with bytes on disk keeps the
        plan that produced them: a different count would not match the stored
        ranges, and reconcile would throw the whole ``.part`` away. Crash
        recovery bumps ``attempts`` without any failure having occurred — which
        is precisely when there is real progress to protect — so backing off
        there would cost a full re-download every time the process restarted.
        A server strict enough to need fewer connections never gets that far:
        nothing downloads, so nothing is at risk.
        """
        if downloaded > 0:
            return self._segments
        return max(1, self._segments >> max(0, attempts - 1))

    async def _fetch(
        self, task: Task, part: str, provider: UrlProvider, destination: Path, *, offset: int
    ) -> None:
        task_id = task.id
        expected_total = task.total_bytes

        async def reconcile(plan: list[Segment]) -> tuple[dict[int, int], bool]:
            result = await self._segment_repo.reconcile(
                task_id, part, [(s.index, s.start, s.end) for s in plan]
            )
            return result.watermarks, result.fresh

        async def discard() -> None:
            await self._segment_repo.clear(task_id, part)

        already = await self._segment_repo.progress(task_id, part)

        async def on_probe(total: int | None) -> None:
            # The first moment a direct download's size is known. Counting what
            # the segment watermarks say is already on disk; an unsegmented
            # resume has none, so it is checked as if starting over, which errs
            # toward refusing.
            if self._disk is not None and total:
                self._disk.require(max(0, total - already))

        await self._engine.fetch(
            UrlSource(provider),
            destination,
            count=self._segment_count(task.attempts, already),
            reconcile=reconcile,
            on_probe=on_probe,
            on_discard=discard,
            on_sample=lambda sample: self._flush(task_id, part, sample, offset=offset, total=expected_total),
            should_stop=lambda: self._control.is_stopping(task_id),
        )

    async def _flush(
        self,
        task_id: uuid.UUID,
        part: str,
        sample: AggregateSample,
        *,
        offset: int,
        total: int | None,
    ) -> None:
        """Report progress for the whole task, and persist the segment watermarks.

        ``offset`` is what earlier parts already wrote, and ``total`` is the sum
        the plan recorded at enqueue. When the plan could not know the total,
        this falls back to the part's own — imperfect, but monotonic within the
        part and never wrong about bytes.

        Two statements per tick where there used to be one: the task row, and
        one ``bulk_update`` covering every segment. The alternative — each
        segment writing on its own timer — is four staggered writes a second
        that each carry three stale siblings.
        """
        downloaded = offset + sample.downloaded_bytes
        grand_total = total or (offset + sample.total_bytes if sample.total_bytes else None)
        progress = min(100, downloaded * 100 // grand_total) if grand_total else 0
        await self._repo.flush_progress(
            task_id,
            downloaded_bytes=downloaded,
            total_bytes=grand_total,
            progress=progress,
            speed_bps=sample.speed_bps,
            eta_seconds=sample.eta_seconds,
        )
        if sample.segments:
            await self._segment_repo.flush(
                task_id, part, {segment.index: segment.downloaded for segment in sample.segments}
            )
        # A separate, lighter event than the full task snapshot: this fires
        # every flush interval per download, and the browser only needs the
        # numbers that moved.
        frame: dict[str, Any] = {
            "id": str(task_id),
            "downloaded_bytes": downloaded,
            "total_bytes": grand_total,
            "progress": progress,
            "speed_bps": sample.speed_bps,
            "eta_seconds": sample.eta_seconds,
        }
        # Absent rather than empty when the transfer is not segmented: an empty
        # array is a third case the browser would have to distinguish, and a
        # small file or a range-less server legitimately has no segments.
        if sample.segments:
            frame["segments"] = [
                {
                    "index": segment.index,
                    "start": segment.start,
                    "end": segment.end,
                    "downloaded": segment.downloaded,
                    "speed_bps": segment.speed_bps,
                }
                for segment in sample.segments
            ]
        self._hub.publish("progress", frame)

    async def _mark_complete(self, task: Task, destination: Path) -> None:
        task.status = TaskStatus.COMPLETE
        task.progress = 100
        task.speed_bps = 0
        task.eta_seconds = None
        task.file_path = str(destination.relative_to(self._root))
        task.file_size = destination.stat().st_size
        # The finished file is the honest final count: the byte totals the
        # download reported were of the parts, which muxing has just consumed.
        task.downloaded_bytes = task.file_size
        task.total_bytes = task.file_size
        task.completed_at = now()
        task.error = None
        task.error_code = None
        await task.save(
            update_fields=[
                "status", "progress", "speed_bps", "eta_seconds", "file_path", "file_size",
                "downloaded_bytes", "total_bytes", "completed_at", "error", "error_code",
            ]
        )
        # Transient state: the file exists now, so the plan that built it is
        # dead weight. Cleared per task rather than per part — a YouTube task
        # whose video succeeded and whose audio then failed will retry, and
        # rebuilding the video plan at zero would re-download a finished part.
        await self._segment_repo.clear(task.id)
        self._emit(task)
        logger.success("{}|completed {} -> {}", self._name, task.id, task.file_path)

    async def _wait_for_space(self, task: Task, error: Error, *, refund: bool = False) -> None:
        """Back to the queue until the disk has room, keeping whatever is on disk.

        Not a failure: the retry budget is for sources that misbehave, and a
        full disk is neither the source's fault nor something a retry fixes.
        ``refund`` hands back the attempt ``run_task`` counted, when the check
        tripped after it.
        """
        if refund:
            task.attempts = max(0, task.attempts - 1)
        task.status = TaskStatus.PENDING
        task.error = error.message
        task.error_code = error.type.value if error.type else None
        task.speed_bps = 0
        task.eta_seconds = None
        task.next_attempt_at = now() + timedelta(seconds=_SPACE_RECHECK_SECONDS)
        await task.save(
            update_fields=[
                "status", "error", "error_code", "speed_bps", "eta_seconds",
                "next_attempt_at", "attempts",
            ]
        )
        logger.warning(
            "{}|waiting for disk space for {}: {}", self._name, task.id, error.message
        )
        self._emit(task)

    async def _mark_failed(self, task: Task, error: Error) -> None:
        decision = retry_policy.decide(error, attempts=task.attempts, max_attempts=self._max_attempts)
        task.error = error.message
        task.error_code = error.type.value if error.type else None
        task.speed_bps = 0
        task.eta_seconds = None

        if decision.retry:
            task.status = TaskStatus.PENDING
            task.next_attempt_at = now() + timedelta(seconds=decision.delay_seconds)
            logger.warning(
                "{}|retrying {} in {}s (attempt {}): {}",
                self._name,
                task.id,
                decision.delay_seconds,
                task.attempts,
                error.message,
            )
        else:
            task.status = TaskStatus.FAILED
            task.next_attempt_at = None
            logger.error("{}|failed {}: {}", self._name, task.id, error.message)

        await task.save(
            update_fields=["status", "error", "error_code", "speed_bps", "eta_seconds", "next_attempt_at"]
        )
        self._emit(task)


class WorkerPool:
    def __init__(self, workers: list[DownloadWorker], http_client: httpx.AsyncClient) -> None:
        self._workers = workers
        self._http_client = http_client
        self._tasks: list[asyncio.Task[None]] = []

    async def start(self) -> None:
        self._tasks = [asyncio.create_task(worker.run_forever()) for worker in self._workers]
        logger.info("WorkerPool|started {} worker(s)", len(self._tasks))

    async def stop(self) -> None:
        for task in self._tasks:
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        await self._http_client.aclose()
        logger.info("WorkerPool|stopped")


def remove_task_files(root: Path, task_id: uuid.UUID) -> None:
    """Delete a task's whole directory. Used by cancel."""
    shutil.rmtree(task_dir(root, task_id), ignore_errors=True)
