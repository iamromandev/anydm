"""The claim loop.

One coroutine per worker, all sharing a ``DownloadControl`` and pulling from
Postgres. The database arbitrates who gets which row; everything else here is
sequencing and bookkeeping.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import timedelta
from pathlib import Path
from typing import Any

import httpx
from loguru import logger

from src.core.common import now
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.data.repo.download.described import describe
from src.data.repo.download.interface import AttemptRepo, CollectionRepo, DownloadRepo, FileRepo, Opened, SegmentRepo
from src.data.type import ACTIVE_STATUSES, AttemptStatus, DownloadStatus, MediaKind, MirrorStatus, Platform, SegmentPart
from src.lib.event import EventHub
from src.lib.site import error as site_error
from src.lib.site.client import Resolved, SiteClient
from src.lib.site.entry_plan import is_unplanned, leading_number, plan_fields, plan_for
from src.lib.site.subtitles import fetch_subtitle
from src.service.download import retry as retry_policy
from src.service.download.collection_totals import CollectionTotals
from src.service.download.control import DownloadControl
from src.service.download.disk import DiskGuard, is_insufficient_storage, storage_error
from src.service.download.downloader import Stopped
from src.service.download.fragment import FragmentDownloader
from src.service.download.live import Live, LiveStats
from src.service.download.paths import (
    collection_destination,
    container_folder,
    final_path,
    part_path,
    remove_work_files,
)
from src.service.download.post_process import PostProcessor
from src.service.download.progress import AggregateSample
from src.service.download.segment import Segment
from src.service.download.segmented import SegmentedDownloader
from src.service.download.site_subtitles import SubtitleFetcher, save_site_subtitles
from src.service.download.url_source import Target, UrlProvider, UrlSource
from src.service.download.views import DownloadViews, progress_frame

_IDLE_POLL_SECONDS = 5.0
#: How long a download waits before checking the disk again.
_SPACE_RECHECK_SECONDS = 30


class DownloadWorker:
    def __init__(
        self,
        *,
        name: str,
        repo: DownloadRepo,
        segment_repo: SegmentRepo,
        attempts: AttemptRepo,
        files: FileRepo,
        collections: CollectionRepo,
        client: SiteClient,
        engine: SegmentedDownloader,
        post_processor: PostProcessor,
        control: DownloadControl,
        hub: EventHub,
        downloads_root: Path,
        max_attempts: int,
        segments: int,
        live: LiveStats,
        views: DownloadViews,
        totals: CollectionTotals | None = None,
        disk: DiskGuard | None = None,
        fragments: FragmentDownloader | None = None,
        subtitle_fetcher: SubtitleFetcher = fetch_subtitle,
    ) -> None:
        self._name = name
        self._repo = repo
        self._segment_repo = segment_repo
        self._attempts = attempts
        self._files = files
        self._collections = collections
        self._client = client
        self._engine = engine
        self._post_processor = post_processor
        self._control = control
        self._hub = hub
        self._root = downloads_root
        self._max_attempts = max_attempts
        self._segments = segments
        self._live = live
        self._views = views
        self._totals = totals
        self._disk = disk
        self._fragments = fragments
        self._fetch_subtitle = subtitle_fetcher

    async def run_forever(self) -> None:
        while True:
            try:
                download = await self._repo.claim_next()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.error("{}|claim failed: {}", self._name, exc)
                await asyncio.sleep(_IDLE_POLL_SECONDS)
                continue

            if download is None:
                await self._control.wait_for_work(timeout=_IDLE_POLL_SECONDS)
                continue

            await self.run_task(download)

    async def run_task(self, download: Any) -> None:
        # Checked before the attempt is counted: a download that never started
        # has not used any of its retries.
        if self._disk is not None:
            try:
                self._disk.require()
            except Error as error:
                await self._wait_for_space(download, error)
                return

        logger.info("{}|starting {}", self._name, download.id)
        download.attempts += 1
        # ``update_fields`` throughout this class, not decoration: ``download``
        # was loaded at claim time and ``flush_progress`` writes straight to the
        # row, so a bare ``save()`` would push this stale copy's zeroes back over
        # every byte count the download has since recorded.
        await download.save(update_fields=["attempts"])
        # One try, through the first mirror still usable: every mirror spent means
        # there is nothing left to fetch from.
        opened = await self._attempts.open(download)
        if opened is None:
            await self._mark_failed(
                download,
                Error.create(
                    code=Code.UNPROCESSABLE_ENTITY,
                    message="No source left to try",
                    error_type=ErrorType.UNPROCESSABLE_ENTITY,
                ),
                None,
            )
            return
        # Claimed: its collection now has one more video downloading.
        await self._refresh_collection(download)

        try:
            platform = describe(download).platform
            site = download.media
            batch = None
            if platform == Platform.SITE and site is not None and is_unplanned(site):
                batch = await self._plan(download, site, opened.url)
            try:
                parts, fragmented = await self._download_parts(download, platform, opened.url, batch)
            except Error as error:
                # A stored format the site no longer offers: plan again, once,
                # from the preset, rather than failing for good.
                if platform != Platform.SITE or error.type != ErrorType.UNPROCESSABLE_ENTITY:
                    raise
                logger.info("{}|re-planning {}: {}", self._name, download.id, error.message)
                parts, fragmented = await self._download_parts(
                    download, platform, opened.url, await self._plan(download, site, opened.url)
                )
            file = await self._files.single(download.id)
            destination = final_path(self._root, download.id, file.path if file else "download")
            await self._post_processor.run(download, parts, destination, fragmented=fragmented)
            destination, folder = await self._into_folder(download, destination)
            if not await self._mark_complete(download, destination, folder):
                # Removed while it finished: the finished file goes with the rest.
                destination.unlink(missing_ok=True)
                await self._yielded(download, opened, counted=True)
                return
            await self._attempts.close(opened, AttemptStatus.COMPLETED)
            await self._save_subtitles(download, opened.url, destination)
        except Stopped:
            # A pause or a cancel already set the row's status, so it is not
            # this worker's to change. But cancel deleted the work directory
            # while this download still held the file open, and the next
            # ``mkdir``/``open`` recreated it — so a canceled download must have
            # its files swept a second time, once the writer has let go.
            logger.info("{}|stopped {}", self._name, download.id)
            await self._attempts.close(opened, AttemptStatus.CANCELLED)
            self._control.clear_stop(download.id)
            self._live.clear(download.id)
            await download.refresh_from_db()
            if download.status == DownloadStatus.CANCELLED:
                remove_work_files(self._root, download.id)
            await self._changed(download)
        except Error as error:
            if is_insufficient_storage(error):
                await self._wait_for_space(download, error, refund=True, opened=opened)
            else:
                await self._mark_failed(download, error, opened)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            # A full disk surfaces from the writer as a bare ``OSError``. It is
            # the one write error worth waiting out rather than failing on.
            storage = storage_error(exc) if isinstance(exc, OSError) else None
            if storage is not None:
                await self._wait_for_space(download, storage, refund=True, opened=opened)
                return
            logger.exception("{}|unexpected failure on {}", self._name, download.id)
            await self._mark_failed(download, Error.internal(message=str(exc)), opened)

    async def _emit(self, download: Any, *, folder: str | None = None) -> None:
        self._hub.publish("download", (await self._views.one(download, folder=folder)).to_json())

    async def _refresh_collection(self, download: Any) -> None:
        if self._totals is not None and download.parent_id is not None:
            await self._totals.refresh(download.parent_id)

    async def _changed(self, download: Any, *, folder: str | None = None) -> None:
        """Publish a status change, and bring a collection video's collection up to date with it."""
        await self._emit(download, folder=folder)
        await self._refresh_collection(download)

    async def _into_folder(self, download: Any, destination: Path) -> tuple[Path, str | None]:
        """Where the finished file lives, and that folder relative to the download root.

        A standalone download stays in its work folder. A collection's video
        moves into the collection's folder, before COMPLETE: a crash here
        requeues it and the move runs again, replacing a file of the same name.
        The folder is derived from the collection's row, never stored.
        """
        if download.parent_id is None:
            return destination, None
        collection = await self._collections.get_active_by_id(download.parent_id)
        if collection is None:
            return destination, None
        video_id = describe(download).ref if download.media else str(download.id)
        path = container_folder(collection)
        moved = collection_destination(self._root, path, destination.name, video_id)
        moved.parent.mkdir(parents=True, exist_ok=True)
        destination.replace(moved)
        remove_work_files(self._root, download.id)
        return moved, path

    async def _plan(self, download: Any, site: Any, url: str) -> dict[str, Resolved]:
        """Formats for the preset, from the one extraction that also gives their URLs (v0.5).

        ``site`` is the download's ``Media``: the plan's title, kind and format ids land there.
        """
        info, resolved = await self._client.open(url)
        if info.is_live:
            raise site_error.live_not_supported()
        file = await self._files.single(download.id)
        planned = plan_fields(
            info,
            plan_for(info.formats, site.preset),
            preset=site.preset,
            title=site.title,
            number=leading_number(file.path if file else ""),
        )
        download.total_size = planned.total_bytes
        await download.save(update_fields=["total_size"])
        site.title = planned.title
        site.kind = planned.media_kind
        site.video_format = planned.video_format
        site.audio_format = planned.audio_format
        await site.save(update_fields=["title", "kind", "video_format", "audio_format"])
        await self._files.set_single(download.id, path=planned.filename, mime_type=planned.mime_type)
        await self._emit(download)
        return resolved

    async def _download_parts(
        self, download: Any, platform: Platform, url: str, batch: dict[str, Resolved] | None = None
    ) -> tuple[dict[str, Path], frozenset[str]]:
        """Fetch every stream the plan names, resuming any ``.part`` already there.

        Returns the parts by name, and the names of those yt-dlp's downloader fetched.
        """
        if platform == Platform.DIRECT:
            destination = part_path(self._root, download.id, SegmentPart.FILE.value)
            source_url = url

            async def direct() -> str:
                return source_url

            await self._fetch(download, SegmentPart.FILE, direct, destination, offset=0)
            return {SegmentPart.FILE.value: destination}, frozenset()

        site = download.media
        wanted = [
            (part, format_id)
            for part, format_id in ((SegmentPart.VIDEO, site.video_format), (SegmentPart.AUDIO, site.audio_format))
            if format_id
        ]
        if not wanted:
            raise Error.internal(message="Download names no format to download")

        # Always re-resolved, and once for every part of this attempt: these
        # URLs expire within hours and bind to the requesting IP, so a stored
        # one is worthless on a resume, and one extraction per part would double
        # what the site sees (and what trips YouTube's bot check).
        source_url = url
        if batch is None:
            batch = await self._client.resolve(source_url, [format_id for _, format_id in wanted])

        parts: dict[str, Path] = {}
        fragmented: set[str] = set()
        # Bytes already on disk from earlier parts. Without this the second part
        # would restart the percentage at zero and the UI would run backwards.
        offset = 0
        for part, format_id in wanted:
            destination = part_path(self._root, download.id, part.value)
            if batch[format_id].fragmented:
                # HLS, DASH and the like: only yt-dlp's downloader fetches these,
                # and it extracts the page itself, so the batch URL goes unused.
                await self._fetch_fragments(download, part, source_url, format_id, destination, offset=offset)
                fragmented.add(part.value)
            else:
                handed = [batch[format_id]]

                # The batch answers the first ask; a URL that expires mid-transfer
                # is resolved again, for this part alone. Bound as defaults because
                # the loop variables would otherwise be read at call time.
                async def provider(format_id: str = format_id, handed: list[Resolved] = handed) -> Target:
                    resolved = (
                        handed.pop() if handed else (await self._client.resolve(source_url, [format_id]))[format_id]
                    )
                    return Target(resolved.url, resolved.headers)

                await self._fetch(download, part, provider, destination, offset=offset)
            parts[part.value] = destination
            offset += destination.stat().st_size
        return parts, frozenset(fragmented)

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
        """
        if downloaded > 0:
            return self._segments
        return max(1, self._segments >> max(0, attempts - 1))

    async def _fetch(
        self, download: Any, part: SegmentPart, provider: UrlProvider, destination: Path, *, offset: int
    ) -> None:
        download_id = download.id
        expected_total = download.total_size
        collection_id = download.parent_id

        async def reconcile(plan: list[Segment]) -> tuple[dict[int, int], bool]:
            result = await self._segment_repo.reconcile(download_id, part, [(s.index, s.start, s.end) for s in plan])
            return result.watermarks, result.fresh

        async def discard() -> None:
            await self._segment_repo.clear(download_id, part)

        already = await self._segment_repo.progress(download_id, part)

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
            count=self._segment_count(download.attempts, already),
            reconcile=reconcile,
            on_probe=on_probe,
            on_discard=discard,
            on_sample=lambda sample: self._flush(
                download_id, part, sample, offset=offset, total=expected_total, collection_id=collection_id
            ),
            should_stop=lambda: self._control.is_stopping(download_id),
        )

    async def _fetch_fragments(
        self, download: Any, part: SegmentPart, page_url: str, format_id: str, destination: Path, *, offset: int
    ) -> None:
        if self._fragments is None:
            raise Error.internal(message="This worker has no fragment downloader")
        download_id = download.id
        expected_total = download.total_size
        collection_id = download.parent_id
        await self._fragments.fetch(
            page_url,
            format_id,
            destination,
            on_sample=lambda sample: self._flush(
                download_id, part, sample, offset=offset, total=expected_total, collection_id=collection_id
            ),
            should_stop=lambda: self._control.is_stopping(download_id),
        )

    async def _flush(
        self,
        download_id: uuid.UUID,
        part: SegmentPart,
        sample: AggregateSample,
        *,
        offset: int,
        total: int | None,
        collection_id: uuid.UUID | None = None,
    ) -> None:
        """Bytes to the row, watermarks to the segments, and the moving numbers to
        ``LiveStats`` and one light frame.

        ``offset`` is what earlier parts already wrote, and ``total`` is the sum
        the plan recorded at enqueue. When the plan could not know the total,
        this falls back to the part's own — imperfect, but monotonic within the
        part and never wrong about bytes.
        """
        downloaded = offset + sample.downloaded_bytes
        grand_total = total or (offset + sample.total_bytes if sample.total_bytes else None)
        await self._repo.flush_progress(download_id, downloaded_size=downloaded, total_size=grand_total)
        if sample.segments:
            await self._segment_repo.flush(
                download_id, part, {segment.index: segment.downloaded for segment in sample.segments}
            )
        live = Live(speed_bps=sample.speed_bps, eta_seconds=sample.eta_seconds)
        self._live.set(download_id, live)
        self._hub.publish(
            "progress",
            progress_frame(
                download_id,
                collection_id=collection_id,
                downloaded_bytes=downloaded,
                total_bytes=grand_total,
                live=live,
                segments=sample.segments or None,
            ),
        )

    async def _save_subtitles(self, download: Any, url: str, destination: Path) -> None:
        """A site video's subtitles beside it (#102). Never fails the download: it's already done."""
        described = describe(download)
        if described.platform != Platform.SITE or described.media_kind != MediaKind.VIDEO:
            return
        try:
            saved = await save_site_subtitles(self._client, url, destination, self._fetch_subtitle)
        except Exception as exc:
            logger.warning("{}|subtitles for {} not saved: {}", self._name, download.id, exc)
            return
        if saved:
            logger.info("{}|saved {} subtitle file(s) for {}", self._name, len(saved), download.id)

    async def _mark_complete(self, download: Any, destination: Path, folder: str | None = None) -> bool:
        """Record the finished file. False when the download was removed while it finished."""
        size = destination.stat().st_size
        # The finished file is the honest final count: the byte totals the
        # download reported were of the parts, which muxing has just consumed.
        fields = {
            "status": DownloadStatus.COMPLETED,
            "downloaded_size": size,
            "total_size": size,
            "completed_at": now(),
            "error": None,
            "error_code": None,
        }
        # A pause that landed as the last bytes did loses to them: the file is whole.
        if not await self._ended(download, fields, over=ACTIVE_STATUSES | {DownloadStatus.PAUSED}):
            return False
        await self._files.finish_single(download.id, path=destination.name, size_bytes=size)
        self._live.clear(download.id)
        # Transient state: the file exists now, so the plan that built it is
        # dead weight. Cleared per download rather than per part — a site
        # download whose video succeeded and whose audio then failed will
        # retry, and rebuilding the video plan at zero would re-download it.
        await self._segment_repo.clear(download.id)
        await self._changed(download, folder=folder)
        logger.success("{}|completed {} -> {}/{}", self._name, download.id, folder or download.id, destination.name)
        return True

    async def _wait_for_space(
        self, download: Any, error: Error, *, refund: bool = False, opened: Opened | None = None
    ) -> None:
        """Back to the queue until the disk has room, keeping whatever is on disk.

        Not a failure: the retry budget is for sources that misbehave, and a
        full disk is neither the source's fault nor something a retry fixes.
        ``refund`` hands back the attempt ``run_task`` counted, when the check
        tripped after it.
        """
        fields = {
            "status": DownloadStatus.PENDING,
            "error": error.message,
            "error_code": error.type.value if error.type else None,
            "next_attempt_at": now() + timedelta(seconds=_SPACE_RECHECK_SECONDS),
            "attempts": max(0, download.attempts - 1) if refund else download.attempts,
        }
        if not await self._ended(download, fields):
            await self._yielded(download, opened, counted=refund)
            return
        if opened is not None:
            # Not the source's doing: the try ends without counting against its mirror.
            await self._attempts.close(opened, AttemptStatus.CANCELLED)
        logger.warning("{}|waiting for disk space for {}: {}", self._name, download.id, error.message)
        self._live.clear(download.id)
        await self._changed(download)

    async def _mark_failed(self, download: Any, error: Error, opened: Opened | None) -> None:
        """Retry on the same mirror while the policy allows; then fail over to the next one, or fail for good."""
        decision = retry_policy.decide(error, attempts=download.attempts, max_attempts=self._max_attempts)
        spent = MirrorStatus.EXHAUSTED if download.attempts >= self._max_attempts else MirrorStatus.FAILED
        # Asked, not acted on: the mirror is retired only once this outcome is written.
        failover = not decision.retry and opened is not None and await self._attempts.spare(opened)
        fields: dict[str, Any] = {
            "error": error.message,
            "error_code": error.type.value if error.type else None,
            "attempts": download.attempts,
        }
        if decision.retry:
            fields |= {
                "status": DownloadStatus.PENDING,
                "next_attempt_at": now() + timedelta(seconds=decision.delay_seconds),
            }
        elif failover:
            # This mirror is spent and another is left: start over on it, with a
            # fresh retry budget, since its failures were the other source's.
            fields |= {"status": DownloadStatus.PENDING, "attempts": 0, "next_attempt_at": None}
        else:
            fields |= {"status": DownloadStatus.FAILED, "next_attempt_at": None}
        if not await self._ended(download, fields):
            await self._yielded(download, opened, counted=True)
            return
        if opened is not None:
            await self._attempts.close(opened, AttemptStatus.FAILED)
            if not decision.retry:
                await self._attempts.retire(opened, spent)

        if decision.retry:
            logger.warning(
                "{}|retrying {} in {}s (attempt {}): {}",
                self._name,
                download.id,
                decision.delay_seconds,
                download.attempts,
                error.message,
            )
        elif failover:
            logger.warning("{}|failing over {} to its next mirror: {}", self._name, download.id, error.message)
        else:
            logger.error("{}|failed {}: {}", self._name, download.id, error.message)
        self._live.clear(download.id)
        await self._changed(download)

    async def _ended(
        self, download: Any, fields: dict[str, Any], *, over: frozenset[DownloadStatus] = ACTIVE_STATUSES
    ) -> bool:
        """Write how the try ended onto the row and onto ``download``, unless a person got there first."""
        if not await self._repo.end_try(download.id, fields, over=over):
            return False
        for name, value in fields.items():
            setattr(download, name, value)
        return True

    async def _yielded(self, download: Any, opened: Opened | None, *, counted: bool) -> None:
        """The person paused or removed the download while its try ran, and their status stands.

        The try closes as cancelled, as a clean stop would close it, and costs
        neither its mirror nor the retry budget: the person ended it, not the source.
        """
        logger.info("{}|{} was paused or removed during its try; leaving it as it is", self._name, download.id)
        if opened is not None:
            await self._attempts.close(opened, AttemptStatus.CANCELLED)
        self._control.clear_stop(download.id)
        self._live.clear(download.id)
        # Read first: a resume since then has already reset the count.
        await download.refresh_from_db()
        if counted and download.attempts > 0:
            download.attempts -= 1
            await download.save(update_fields=["attempts"])
        if download.status == DownloadStatus.CANCELLED or download.deleted_at is not None:
            remove_work_files(self._root, download.id)
        await self._changed(download)


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
