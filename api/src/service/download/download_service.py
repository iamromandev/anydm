from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, get_args

from loguru import logger

from src.core.base import BaseService
from src.core.common import now
from src.core.error import Error
from src.core.success import Meta
from src.data.repo.download.described import describe
from src.data.repo.download.interface import CollectionRepo, DownloadRepo, FileRepo, PositionRepo, SegmentRepo
from src.data.schema.play import PlaybackSchema
from src.data.schema.transfer import CollectionSchema, DownloadSchema, DownloadSummarySchema
from src.data.type import DOWNLOAD_GROUPS, DownloadSort, DownloadStatus, Platform, Preset
from src.lib.event import EventHub
from src.lib.media.sidecar import Sidecar, SidecarSource, folder_listing, match_sidecars
from src.lib.site import error as site_error
from src.lib.site.client import SiteClient
from src.lib.site.entry_plan import plan_fields
from src.lib.site.format import select_plan
from src.service.download.collection_service import CollectionService, remove_collection_video_files
from src.service.download.collection_totals import CollectionTotals
from src.service.download.control import DownloadControl
from src.service.download.direct import ensure_fetchable, filename_from_url
from src.service.download.disk import DiskGuard
from src.service.download.folders import inside
from src.service.download.live import LiveStats
from src.service.download.paths import container_folder, remove_work_files, standalone_folder
from src.service.download.torrent_service import TorrentService
from src.service.download.views import DownloadViews

#: Which rows each bulk action applies to. Seeding is in the pause set because
#: only a torrent can be seeding and the engine accepts pausing one; failed is in
#: both the resume set and the clear set, because a failure is equally "try
#: again" and "give up on this".
BULK_SCOPES: dict[str, frozenset[DownloadStatus]] = {
    "pause_all": frozenset({DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.SEEDING}),
    "resume_all": frozenset({DownloadStatus.PAUSED, DownloadStatus.FAILED}),
    "clear_finished": frozenset({DownloadStatus.COMPLETED, DownloadStatus.FAILED}),
}


@dataclass(frozen=True, slots=True)
class TorrentPlay:
    """A torrent still downloading, to play through rqbit's stream (#95)."""

    info_hash: str
    file_index: int


class DownloadService(BaseService):
    #: Stopping this close to the end counts as having watched it (#96).
    WATCHED_WITHIN_S = 30.0

    def __init__(
        self,
        repo: DownloadRepo,
        collections: CollectionService,
        collection_repo: CollectionRepo,
        segment_repo: SegmentRepo,
        files: FileRepo,
        positions: PositionRepo,
        client: SiteClient,
        control: DownloadControl,
        hub: EventHub,
        downloads_root: Path,
        torrents: TorrentService,
        views: DownloadViews,
        totals: CollectionTotals,
        live: LiveStats,
        disk: DiskGuard | None = None,
    ) -> None:
        super().__init__()
        self._repo = repo
        self._collections = collections
        self._collection_repo = collection_repo
        self._segment_repo = segment_repo
        self._files = files
        self._positions = positions
        self._client = client
        self._control = control
        self._hub = hub
        self._root = downloads_root
        self._torrents = torrents
        self._views = views
        self._totals = totals
        self._live = live
        self._disk = disk

    def _require_space(self, extra_bytes: int | None = None) -> None:
        if self._disk is not None:
            self._disk.require(extra_bytes or 0)

    async def enqueue_media(self, url: str, preset: Preset) -> DownloadSchema:
        """Resolve the plan now, move the bytes later, for any site.

        Everything that can fail on the caller's behalf (an unsupported link, a
        private video, a live stream, a preset the site cannot satisfy) fails
        here, as a 4xx they see immediately. What reaches the queue is a
        decision, not a guess.
        """
        info = await self._client.extract(url)
        if info.is_live:
            raise site_error.live_not_supported()
        plan = select_plan(info.formats, preset)
        # Refused before the row exists, so a 507 leaves nothing behind. An
        # estimated size counts here; an unknown one is checked against the
        # minimum alone, and the worker checks again once the probe knows.
        self._require_space(plan.expected_bytes)
        planned = plan_fields(info, plan, preset=preset, title=info.title, number="")
        download = await self._repo.create_site(
            # The site's own page for the video, not the link as pasted: a short
            # link and a playlist's entry for the same video are then one address.
            url=info.webpage_url or url,
            provider=info.extractor,
            download={"status": DownloadStatus.PENDING, "total_size": planned.total_bytes},
            media={
                "title": planned.title,
                "kind": planned.media_kind,
                "preset": preset,
                "video_format": planned.video_format,
                "audio_format": planned.audio_format,
            },
            filename=planned.filename,
            mime_type=planned.mime_type,
        )
        # Workers share this process, so a queued download starts in
        # milliseconds rather than on the next poll tick.
        self._control.wake()
        return await self._published(download)

    async def enqueue_url(self, url: str) -> DownloadSchema:
        """Queue a plain HTTP download — anything that is not a media platform."""
        ensure_fetchable(url)
        # The size is not known until a worker probes the source, so only the
        # minimum can be checked here.
        self._require_space()
        name = filename_from_url(url)
        download = await self._repo.create_direct(url=url, download={"status": DownloadStatus.PENDING}, filename=name)
        self._control.wake()
        return await self._published(download)

    async def list_items(
        self, page: int, page_size: int, group: str = "all", sort: str = "-created_at"
    ) -> tuple[list[DownloadSchema | CollectionSchema], Meta]:
        """One page of the tagged list, narrowed to a sidebar filter, in the view's order.

        The filter is named rather than spelled out as statuses so that it and
        the counts beside it can't drift: both read ``DOWNLOAD_GROUPS``.
        """
        if group != "all" and group not in DOWNLOAD_GROUPS:
            raise Error.bad_request(message=f"Unknown group: {group}")
        # Checked here as well as at the route: this value reaches ORDER BY, and
        # a caller inside the process has no FastAPI in front of it.
        if sort not in get_args(DownloadSort):
            raise Error.bad_request(message=f"Cannot sort by: {sort}")
        statuses = None if group == "all" else sorted(DOWNLOAD_GROUPS[group])
        items, meta = await self._repo.list_items(page, page_size, statuses, sort, self._live.speeds())
        download_ids = [item_id for kind, item_id in items if kind == "download"]
        collection_ids = [item_id for kind, item_id in items if kind == "collection"]
        downloads = {s.id: s for s in await self._views.many(await self._repo.by_ids(download_ids))}
        collections = {s.id: s for s in await self._totals.schemas(await self._collection_repo.by_ids(collection_ids))}
        ordered: list[DownloadSchema | CollectionSchema] = []
        for kind, item_id in items:
            found = downloads.get(item_id) if kind == "download" else collections.get(item_id)
            if found is not None:
                ordered.append(found)
        return ordered, meta

    async def bulk(self, action: str, *, delete_files: bool = False) -> int:
        """Apply one action to every row it makes sense for.

        Which rows those are is decided here rather than by the caller: the
        preconditions already live on ``pause``, ``resume`` and ``cancel``, and
        every row goes through those same methods, so a torrent is paused by the
        engine and a direct download by the worker, exactly as a single action
        would do it. One row refusing does not end the sweep.
        """
        scope = BULK_SCOPES.get(action)
        if scope is None:
            raise Error.bad_request(message=f"Unknown bulk action: {action}")

        affected = 0
        # A collection's videos move in one update rather than a row at a time,
        # and each collection they belong to is brought up to date once.
        touched: set[uuid.UUID] = set()
        if action == "pause_all":
            running, touched = await self._collection_repo.pause_all()
            for video in running:
                self._control.request_stop(video)
        elif action == "resume_all":
            touched = await self._collection_repo.resume_all()
            self._control.wake()

        # Standalone downloads one at a time, as before.
        for row in await self._repo.by_statuses(sorted(scope)):
            try:
                if action == "pause_all":
                    await self.pause(row.id)
                elif action == "resume_all":
                    await self.resume(row.id)
                else:
                    # Only a finished row has anything worth keeping; asking to
                    # keep the remains of a failure is refused by ``cancel``.
                    keepable = row.status in (DownloadStatus.COMPLETED, DownloadStatus.SEEDING)
                    await self.cancel(row.id, delete_files=delete_files or not keepable)
                affected += 1
            except Error as error:
                logger.warning("{}|bulk {} skipped {}: {}", self._tag, action, row.id, error.message)

        # Whole collections whose computed status is finished.
        if action == "clear_finished":
            finished, _ = await self._repo.list_items(1, 10_000, sorted(scope), "created_at", {})
            for kind, item_id in finished:
                if kind != "collection":
                    continue
                try:
                    await self._collections.cancel(item_id, delete_files=delete_files)
                    affected += 1
                except Error as error:
                    logger.warning("{}|bulk {} skipped {}: {}", self._tag, action, item_id, error.message)

        for collection_id in touched:
            await self._totals.refresh(collection_id)
            affected += 1
        return affected

    async def summary(self) -> DownloadSummarySchema:
        return await self._repo.summary()

    async def get(self, download_id: uuid.UUID) -> DownloadSchema:
        return await self._views.one(await self._require(download_id))

    async def save_playback(
        self,
        download_id: uuid.UUID,
        file_index: int | None,
        *,
        position_seconds: float,
        duration_seconds: float,
    ) -> PlaybackSchema:
        """Record where a file was left in the player, so it resumes on any device (#96).

        Stopping within ``WATCHED_WITHIN_S`` of the end marks the file watched
        and clears where to resume, so it opens from the start next time. A
        file once watched stays watched when played again.
        """
        await self._require(download_id)
        index = file_index or 0
        file = await self._files.get(download_id, index)
        if file is None:
            raise Error.not_found(message=f"Download has no file at index {index}")
        previous = (await self._positions.by_files([file.id])).get(file.id)
        near_end = duration_seconds > 0 and duration_seconds - position_seconds <= self.WATCHED_WITHIN_S
        row = await self._positions.save(
            file.id,
            position_seconds=0.0 if near_end else position_seconds,
            duration_seconds=duration_seconds,
            watched=near_end or bool(previous and previous.watched),
        )
        return PlaybackSchema(
            position_seconds=row.position_seconds, duration_seconds=row.duration_seconds, watched=row.watched
        )

    async def resolve_file(self, download_id: uuid.UUID, file_index: int | None) -> tuple[Path, str, str]:
        """A finished file.

        409 rather than 404 while a download is still running: the resource will
        exist, just not yet, and a polling client has to tell "wait" from "never".
        """
        download = await self._require(download_id)
        if describe(download).platform == Platform.TORRENT:
            if file_index is None:
                raise Error.not_found(message="A torrent's files are fetched by index")
            return await self._torrents.resolve_file(download_id, file_index)
        if file_index not in (None, 0):
            raise Error.not_found(message="Only a torrent has files by index")
        if download.status != DownloadStatus.COMPLETED:
            raise Error.conflict(message=f"Download is {download.status.value}, not complete")
        file = await self._files.single(download_id)
        if file is None:
            raise Error.not_found(message="Download has no file")
        path = await self._disk_path(download, file.path)
        if not path.is_file():
            raise Error.not_found(message="File is no longer on disk")
        return path, file.path, file.mime_type or "application/octet-stream"

    async def resolve_media_file(self, download_id: uuid.UUID, file_index: int | None) -> tuple[Path, str, int | None]:
        """The file Play reads from disk, and its index in a torrent (#94).

        A download's own file, or one of a torrent's: the one asked for, else
        its largest selected media file.
        """
        download = await self._require(download_id)
        if describe(download).platform == Platform.TORRENT:
            if file_index is None:
                file_index = await self._torrents.media_file_index(download_id)
            path, filename, _ = await self._torrents.resolve_file(download_id, file_index)
            return path, filename, file_index
        if file_index is not None:
            raise Error.not_found(message="Only a torrent has files by index")
        path, filename, _ = await self.resolve_file(download_id, None)
        return path, filename, None

    async def subtitle_files(
        self, download_id: uuid.UUID, file_index: int | None
    ) -> list[tuple[Sidecar, SidecarSource]]:
        """The subtitle files that go with the file Play opens, and where to read each (#101).

        A torrent's come from its file list. A download's are its neighbours on
        disk, beside it or in a subtitles folder there, once it's finished.
        """
        download = await self._require(download_id)
        if describe(download).platform == Platform.TORRENT:
            return await self._torrents.subtitle_files(download_id, file_index)
        if download.status != DownloadStatus.COMPLETED:
            return []
        path, _, _ = await self.resolve_file(download_id, None)
        listing = await asyncio.to_thread(folder_listing, path.parent)
        return [(sidecar, path.parent / sidecar.path) for sidecar in match_sidecars(path.name, listing)]

    async def torrent_play(self, download_id: uuid.UUID, file_index: int | None) -> TorrentPlay | None:
        """The torrent to stream when Play is pressed on a torrent still downloading (#95).

        ``None`` when it plays from disk instead: it isn't a torrent, or it has
        finished. A paused torrent is resumed first, since a stream from it
        would stall; it keeps downloading after the player closes.
        """
        download = await self._require(download_id)
        if describe(download).platform != Platform.TORRENT or download.status in (
            DownloadStatus.COMPLETED,
            DownloadStatus.SEEDING,
        ):
            return None
        if download.status not in (DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED):
            raise Error.conflict(message=f"Download is {download.status.value}; there's nothing to play")
        index = await self._torrents.media_file_index(download_id, file_index)
        if download.status == DownloadStatus.PAUSED:
            await self._torrents.resume(download_id)
        info_hash = describe(download).info_hash or ""
        return TorrentPlay(info_hash=info_hash, file_index=index)

    async def pause(self, download_id: uuid.UUID) -> Any:
        """Signal a running transfer to stop between chunks, keeping the ``.part``.

        The status is written here rather than by the worker so the caller's
        next read reflects the pause immediately, even mid-chunk.
        """
        download = await self._require(download_id)
        if describe(download).platform == Platform.TORRENT:
            return await self._torrents.pause(download_id)
        if download.status not in (DownloadStatus.PENDING, DownloadStatus.DOWNLOADING):
            raise Error.conflict(message=f"Cannot pause a download that is {download.status.value}")

        self._control.request_stop(download_id)
        download.status = DownloadStatus.PAUSED
        await download.save(update_fields=["status"])
        self._live.clear(download.id)
        schema = await self._published(download)
        await self._refresh_collection(download)
        return schema

    async def resume(self, download_id: uuid.UUID) -> Any:
        """Put a paused or failed download back in the queue, from where its bytes stopped.

        ``attempts`` resets because this is a fresh decision by a person, not a
        continuation of the automatic retry budget that gave up.
        """
        download = await self._require(download_id)
        if describe(download).platform == Platform.TORRENT:
            return await self._torrents.resume(download_id)
        if download.status not in (DownloadStatus.PAUSED, DownloadStatus.FAILED):
            raise Error.conflict(message=f"Cannot resume a download that is {download.status.value}")

        # A worker still holding it stops at its next chunk, as the pause asked, and
        # the download is claimed afresh once it lets go: two workers never share a try.
        if not self._control.holds(download_id):
            self._control.clear_stop(download_id)
        download.status = DownloadStatus.PENDING
        download.error = None
        download.error_code = None
        download.attempts = 0
        download.next_attempt_at = None
        await download.save(update_fields=["status", "error", "error_code", "attempts", "next_attempt_at"])
        self._control.wake()
        schema = await self._published(download)
        await self._refresh_collection(download)
        return schema

    async def cancel(self, download_id: uuid.UUID, *, delete_files: bool = True) -> None:
        """Stop the download, soft-delete the row, and take the files or leave them.

        Keeping the files is only offered for a download that finished. A
        ``.part`` outlives its row as so many bytes nothing can describe: the
        watermarks that say which ranges are sound live in ``segment``, and
        cancelling clears those.
        """
        download = await self._require(download_id)
        if not delete_files and download.status not in (DownloadStatus.COMPLETED, DownloadStatus.SEEDING):
            raise Error.conflict(message=f"Cannot keep the files of a download that is {download.status.value}")
        if describe(download).platform == Platform.TORRENT:
            return await self._torrents.cancel(download_id, delete_files=delete_files)
        self._control.request_stop(download_id)
        if delete_files:
            remove_work_files(self._root, download_id)
            file = await self._files.single(download_id)
            if file is not None:
                # Its file and the subtitles beside it, never the folder it shares.
                remove_collection_video_files(await self._disk_path(download, file.path))
        await self._segment_repo.clear(download_id)
        download.status = DownloadStatus.CANCELLED
        download.deleted_at = now()
        await download.save(update_fields=["status", "deleted_at"])
        self._live.clear(download.id)
        await self._published(download)
        await self._refresh_collection(download)

    async def _refresh_collection(self, download: Any) -> None:
        """Bring a collection video's collection up to date after a change to it."""
        if download.parent_id is not None:
            await self._totals.refresh(download.parent_id)

    async def _folder_of(self, download: Any) -> str | None:
        """The download's folder for its schema: derived when finished, else ``None``."""
        if download.status != DownloadStatus.COMPLETED:
            return None
        if download.parent_id is not None:
            collection = await self._collection_repo.get_active_by_id(download.parent_id)
            if collection is None:
                return None
            return container_folder(collection)
        return standalone_folder(download.id)

    async def _disk_path(self, download: Any, filename: str) -> Path:
        """The finished file on disk: its derived folder plus its file name."""
        if download.parent_id is not None:
            collection = await self._collection_repo.get_active_by_id(download.parent_id)
            if collection is None:
                raise Error.not_found(message="Collection not found")
            return inside(self._root, container_folder(collection)) / Path(filename).name
        return inside(self._root, standalone_folder(download.id)) / Path(filename).name

    async def _published(self, download: Any) -> DownloadSchema:
        """Serialise, announce and hand back.

        Publishing here rather than only in the worker is what makes a change
        made through the API reach every open browser immediately.
        """
        schema = await self._views.one(download, folder=await self._folder_of(download))
        self._hub.publish("download", schema.to_json())
        return schema

    async def _require(self, download_id: uuid.UUID) -> Any:
        download = await self._repo.get_active_by_id(download_id)
        if download is None:
            raise Error.not_found(message="Download not found")
        return download
