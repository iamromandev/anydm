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
from src.core.type import ErrorType
from src.data.repo.download.interface import CollectionRepo, DownloadRepo, FileRepo, SegmentRepo
from src.data.type import DownloadStatus, MediaKind, Platform, SegmentPart
from src.lib.event import EventHub
from src.lib.site import error as site_error
from src.lib.site.client import Resolved, SiteClient
from src.lib.site.entry_plan import is_unplanned, number_of, plan_fields, plan_for
from src.lib.site.subtitles import fetch_subtitle
from src.service.download import retry as retry_policy
from src.service.download.collection_totals import CollectionTotals
from src.service.download.control import DownloadControl
from src.service.download.disk import DiskGuard, is_insufficient_storage, storage_error
from src.service.download.downloader import Stopped
from src.service.download.fragment import FragmentDownloader
from src.service.download.live import Live, LiveStats
from src.service.download.paths import collection_destination, final_path, part_path, remove_work_files
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
        # Claimed: its collection now has one more video downloading.
        await self._refresh_collection(download)

        try:
            site = download.site_detail
            batch = None
            if download.platform == Platform.SITE and site is not None and is_unplanned(site):
                batch = await self._plan(download, site)
            try:
                parts, fragmented = await self._download_parts(download, batch)
            except Error as error:
                # A stored format the site no longer offers: plan again, once,
                # from the preset, rather than failing for good.
                if download.platform != Platform.SITE or error.type != ErrorType.UNPROCESSABLE_ENTITY:
                    raise
                logger.info("{}|re-planning {}: {}", self._name, download.id, error.message)
                parts, fragmented = await self._download_parts(download, await self._plan(download, site))
            file = await self._files.single(download.id)
            destination = final_path(self._root, download.id, file.path if file else "download")
            await self._post_processor.run(download, parts, destination, fragmented=fragmented)
            destination, folder = await self._into_folder(download, destination)
            await self._mark_complete(download, destination, folder)
            await self._save_subtitles(download, destination)
        except Stopped:
            # A pause or a cancel already set the row's status, so it is not
            # this worker's to change. But cancel deleted the work directory
            # while this download still held the file open, and the next
            # ``mkdir``/``open`` recreated it — so a canceled download must have
            # its files swept a second time, once the writer has let go.
            logger.info("{}|stopped {}", self._name, download.id)
            self._control.clear_stop(download.id)
            self._live.clear(download.id)
            await download.refresh_from_db()
            if download.status == DownloadStatus.CANCELED:
                remove_work_files(self._root, download.id)
            await self._changed(download)
        except Error as error:
            if is_insufficient_storage(error):
                await self._wait_for_space(download, error, refund=True)
            else:
                await self._mark_failed(download, error)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            # A full disk surfaces from the writer as a bare ``OSError``. It is
            # the one write error worth waiting out rather than failing on.
            storage = storage_error(exc) if isinstance(exc, OSError) else None
            if storage is not None:
                await self._wait_for_space(download, storage, refund=True)
                return
            logger.exception("{}|unexpected failure on {}", self._name, download.id)
            await self._mark_failed(download, Error.internal(message=str(exc)))

    async def _emit(self, download: Any) -> None:
        self._hub.publish("download", (await self._views.one(download)).to_json())

    async def _refresh_collection(self, download: Any) -> None:
        if self._totals is not None and download.collection_id is not None:
            await self._totals.refresh(download.collection_id)

    async def _changed(self, download: Any) -> None:
        """Publish a status change, and bring a collection video's collection up to date with it."""
        await self._emit(download)
        await self._refresh_collection(download)

    async def _into_folder(self, download: Any, destination: Path) -> tuple[Path, str]:
        """Where the finished file lives, and that folder relative to the download root.

        A standalone download stays in its work folder. A collection's video
        moves into the collection's folder, before COMPLETE: a crash here
        requeues it and the move runs again, replacing a file of the same name.
        """
        if download.collection_id is None:
            return destination, str(download.id)
        collection = await self._collections.get_active_by_id(download.collection_id)
        if collection is None:
            return destination, str(download.id)
        video_id = download.site_detail.video_id if download.site_detail else str(download.id)
        moved = collection_destination(self._root, collection.folder, destination.name, video_id)
        moved.parent.mkdir(parents=True, exist_ok=True)
        destination.replace(moved)
        remove_work_files(self._root, download.id)
        return moved, collection.folder

    async def _plan(self, download: Any, site: Any) -> dict[str, Resolved]:
        """Formats for the preset, from the one extraction that also gives their URLs (v0.5)."""
        info, resolved = await self._client.open(download.source_url)
        if info.is_live:
            raise site_error.live_not_supported()
        file = await self._files.single(download.id)
        planned = plan_fields(
            info,
            plan_for(info.formats, site.preset),
            preset=site.preset,
            title=download.title,
            number=number_of(file.path if file else "", download.position),
        )
        download.media_kind = planned.media_kind
        download.title = planned.title
        download.total_bytes = planned.total_bytes
        await download.save(update_fields=["media_kind", "title", "total_bytes"])
        site.video_format = planned.video_format
        site.audio_format = planned.audio_format
        await site.save(update_fields=["video_format", "audio_format"])
        await self._files.set_single(download.id, path=planned.filename, mime_type=planned.mime_type)
        await self._emit(download)
        return resolved

    async def _download_parts(
        self, download: Any, batch: dict[str, Resolved] | None = None
    ) -> tuple[dict[str, Path], frozenset[str]]:
        """Fetch every stream the plan names, resuming any ``.part`` already there.

        Returns the parts by name, and the names of those yt-dlp's downloader fetched.
        """
        if download.platform == Platform.DIRECT:
            destination = part_path(self._root, download.id, SegmentPart.FILE.value)
            source_url = download.source_url

            async def direct() -> str:
                return source_url

            await self._fetch(download, SegmentPart.FILE, direct, destination, offset=0)
            return {SegmentPart.FILE.value: destination}, frozenset()

        site = download.site_detail
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
        source_url = download.source_url
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
        expected_total = download.total_bytes
        collection_id = download.collection_id

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
        expected_total = download.total_bytes
        collection_id = download.collection_id
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
        await self._repo.flush_progress(download_id, downloaded_bytes=downloaded, total_bytes=grand_total)
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

    async def _save_subtitles(self, download: Any, destination: Path) -> None:
        """A site video's subtitles beside it (#102). Never fails the download: it's already done."""
        if download.platform != Platform.SITE or download.media_kind != MediaKind.VIDEO:
            return
        try:
            saved = await save_site_subtitles(self._client, download.source_url, destination, self._fetch_subtitle)
        except Exception as exc:
            logger.warning("{}|subtitles for {} not saved: {}", self._name, download.id, exc)
            return
        if saved:
            logger.info("{}|saved {} subtitle file(s) for {}", self._name, len(saved), download.id)

    async def _mark_complete(self, download: Any, destination: Path, folder: str) -> None:
        size = destination.stat().st_size
        download.status = DownloadStatus.COMPLETE
        download.folder = folder
        # The finished file is the honest final count: the byte totals the
        # download reported were of the parts, which muxing has just consumed.
        download.downloaded_bytes = size
        download.total_bytes = size
        download.completed_at = now()
        download.error = None
        download.error_code = None
        await download.save(
            update_fields=["status", "folder", "downloaded_bytes", "total_bytes", "completed_at", "error", "error_code"]
        )
        await self._files.finish_single(download.id, path=destination.name, size_bytes=size)
        self._live.clear(download.id)
        # Transient state: the file exists now, so the plan that built it is
        # dead weight. Cleared per download rather than per part — a site
        # download whose video succeeded and whose audio then failed will
        # retry, and rebuilding the video plan at zero would re-download it.
        await self._segment_repo.clear(download.id)
        await self._changed(download)
        logger.success("{}|completed {} -> {}/{}", self._name, download.id, folder, destination.name)

    async def _wait_for_space(self, download: Any, error: Error, *, refund: bool = False) -> None:
        """Back to the queue until the disk has room, keeping whatever is on disk.

        Not a failure: the retry budget is for sources that misbehave, and a
        full disk is neither the source's fault nor something a retry fixes.
        ``refund`` hands back the attempt ``run_task`` counted, when the check
        tripped after it.
        """
        if refund:
            download.attempts = max(0, download.attempts - 1)
        download.status = DownloadStatus.PENDING
        download.error = error.message
        download.error_code = error.type.value if error.type else None
        download.next_attempt_at = now() + timedelta(seconds=_SPACE_RECHECK_SECONDS)
        await download.save(update_fields=["status", "error", "error_code", "next_attempt_at", "attempts"])
        logger.warning("{}|waiting for disk space for {}: {}", self._name, download.id, error.message)
        self._live.clear(download.id)
        await self._changed(download)

    async def _mark_failed(self, download: Any, error: Error) -> None:
        decision = retry_policy.decide(error, attempts=download.attempts, max_attempts=self._max_attempts)
        download.error = error.message
        download.error_code = error.type.value if error.type else None

        if decision.retry:
            download.status = DownloadStatus.PENDING
            download.next_attempt_at = now() + timedelta(seconds=decision.delay_seconds)
            logger.warning(
                "{}|retrying {} in {}s (attempt {}): {}",
                self._name,
                download.id,
                decision.delay_seconds,
                download.attempts,
                error.message,
            )
        else:
            download.status = DownloadStatus.FAILED
            download.next_attempt_at = None
            logger.error("{}|failed {}: {}", self._name, download.id, error.message)

        await download.save(update_fields=["status", "error", "error_code", "next_attempt_at"])
        self._live.clear(download.id)
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
