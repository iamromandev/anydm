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

import httpx
from loguru import logger

from src.core.common import now
from src.core.error import Error
from src.data.db.model import Task
from src.data.repo.download.interface import TaskRepo
from src.data.type import Platform, TaskStatus
from src.lib.youtube import YouTubeClient
from src.service.download import retry as retry_policy
from src.service.download.control import DownloadControl
from src.service.download.downloader import Downloader, Stopped
from src.service.download.paths import final_path, part_path, task_dir
from src.service.download.post_process import PostProcessor
from src.service.download.progress import ProgressSample

_IDLE_POLL_SECONDS = 5.0


class DownloadWorker:
    def __init__(
        self,
        *,
        name: str,
        repo: TaskRepo,
        client: YouTubeClient,
        downloader: Downloader,
        post_processor: PostProcessor,
        control: DownloadControl,
        downloads_root: Path,
        max_attempts: int,
    ) -> None:
        self._name = name
        self._repo = repo
        self._client = client
        self._downloader = downloader
        self._post_processor = post_processor
        self._control = control
        self._root = downloads_root
        self._max_attempts = max_attempts

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
        except Error as error:
            await self._mark_failed(task, error)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.exception("{}|unexpected failure on {}", self._name, task.id)
            await self._mark_failed(task, Error.internal(message=str(exc)))

    async def _download_parts(self, task: Task) -> dict[str, Path]:
        """Fetch every stream the plan names, resuming any ``.part`` already there."""
        if task.platform == Platform.DIRECT:
            destination = part_path(self._root, task.id, "file")
            await self._fetch(task, task.source_url, destination, offset=0)
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
        for name, itag in wanted:
            destination = part_path(self._root, task.id, name)
            # Always re-resolved: these URLs expire within hours and bind to the
            # requesting IP, so a stored one is worthless on a resume.
            url = await self._client.stream_url(task.video_id or "", itag)
            await self._fetch(task, url, destination, offset=offset)
            parts[name] = destination
            offset += destination.stat().st_size
        return parts

    async def _fetch(self, task: Task, url: str, destination: Path, *, offset: int) -> None:
        resume_from = destination.stat().st_size if destination.exists() else 0
        task_id = task.id
        expected_total = task.total_bytes
        await self._downloader.fetch(
            url,
            destination,
            resume_from=resume_from,
            on_sample=lambda sample: self._flush(task_id, sample, offset=offset, total=expected_total),
            should_stop=lambda: self._control.is_stopping(task_id),
        )

    async def _flush(
        self,
        task_id: uuid.UUID,
        sample: ProgressSample,
        *,
        offset: int,
        total: int | None,
    ) -> None:
        """Report progress for the whole task, not for the part in flight.

        ``offset`` is what earlier parts already wrote, and ``total`` is the sum
        the plan recorded at enqueue. When the plan could not know the total,
        this falls back to the part's own — imperfect, but monotonic within the
        part and never wrong about bytes.
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
        logger.success("{}|completed {} -> {}", self._name, task.id, task.file_path)

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
