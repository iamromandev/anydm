"""Torrents, as this API's downloads.

The engine owns the transfer; this service owns the row. It resolves what a
magnet contains, creates the download with its torrent detail and file rows, and
translates the control verbs. Progress is not its job — ``torrent_monitor.py``
does that.
"""

from __future__ import annotations

import mimetypes
import uuid
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from loguru import logger

from src.core.base import BaseService
from src.core.common import now
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.data.repo.category import CategoryRepo
from src.data.repo.download.described import describe
from src.data.repo.download.interface import DownloadRepo, FileRepo
from src.data.schema.torrent import FileSchema, TorrentResolveResponse
from src.data.schema.transfer import DownloadSchema
from src.data.type import DownloadStatus
from src.lib.event import EventHub
from src.lib.media.sidecar import Sidecar, SidecarSource, TorrentFile, match_sidecars
from src.lib.torrent.folder import torrent_folder
from src.lib.torrent.protocol import TorrentClient, TorrentDetails
from src.lib.torrent.source import parse_source
from src.service.category.pick import pick_category
from src.service.download.disk import DiskGuard
from src.service.download.duplicate import already_held
from src.service.download.folders import inside
from src.service.download.live import LiveStats
from src.service.download.views import DownloadViews
from src.service.stream.torrent_source import MEDIA_EXTENSIONS


class TorrentService(BaseService):
    def __init__(
        self,
        repo: DownloadRepo,
        file_repo: FileRepo,
        client: TorrentClient,
        hub: EventHub,
        views: DownloadViews,
        live: LiveStats,
        downloads_root: Path,
        enabled: bool,
        disk: DiskGuard | None = None,
        categories: CategoryRepo | None = None,
    ) -> None:
        super().__init__()
        self._repo = repo
        self._file_repo = file_repo
        self._client = client
        self._hub = hub
        self._views = views
        self._live = live
        self._downloads = downloads_root.resolve()
        self._categories = categories
        self._enabled = enabled
        self._disk = disk

    async def resolve(self, raw: str) -> TorrentResolveResponse:
        """What this magnet contains, without downloading any of it.

        Nothing is written. An abandoned magnet therefore leaves nothing to
        clean up, which is the whole reason resolving precedes enqueueing.
        """
        self._require_enabled()
        source = parse_source(raw)
        details = await self._client.resolve(source)

        return TorrentResolveResponse(
            info_hash=details.info_hash,
            title=details.name,
            total_size=sum(file.size_bytes for file in details.files),
            files=[
                FileSchema(
                    index=file.index,
                    path=file.path,
                    size=file.size_bytes,
                    # Everything is offered ticked; the picker is a way to
                    # remove files, not a puzzle to solve before downloading.
                    selected=True,
                )
                for file in details.files
            ],
        )

    async def enqueue(
        self, raw: str, files: Sequence[int], *, allow_duplicate: bool = False, category_id: uuid.UUID | None = None
    ) -> DownloadSchema:
        """Start a torrent and record it as a download.

        The engine is asked first. Its answer carries the info hash, the real
        file list and the folder it chose, and a row created before that would
        be a guess at all three.
        """
        self._require_enabled()
        category = await pick_category(self._categories, category_id)
        if allow_duplicate:
            # The engine holds one torrent per info hash, so a second copy can't exist.
            raise Error.create(
                code=Code.BAD_REQUEST,
                message="A torrent can only be in the list once",
                error_type=ErrorType.BAD_REQUEST,
            )
        source = parse_source(raw)

        # Resolving before adding buys the rejection below: a selection naming a
        # file the torrent does not have would otherwise be a silently empty download.
        details = await self._client.resolve(source)
        # A torrent already in the list is refused, naming it: a second row would
        # mirror the same engine torrent into the same folder.
        existing = await self._repo.by_info_hash(details.info_hash)
        if existing is not None:
            raise already_held(existing)
        selected = self._validated_selection(details, files)
        total_bytes = sum(file.size_bytes for file in details.files if file.index in selected)

        # Before ``add``: once the engine has the torrent it starts writing, and
        # a refusal after that would leave rqbit filling the disk with no row.
        if self._disk is not None:
            self._disk.require(total_bytes)

        # A folder of its own, under the category's: rqbit writes into exactly
        # this folder, not one named after the torrent inside it (#107). Recorded
        # on the row, so a later change to the category's folder can't lose it.
        folder = torrent_folder(inside(self._downloads, category.folder), details.name, details.info_hash)
        relative = folder.relative_to(self._downloads).as_posix()
        await self._client.add(
            source,
            only_files=sorted(selected) if len(selected) != len(details.files) else [],
            output_folder=str(folder),
        )
        download = await self._repo.create_torrent(
            # A base64 .torrent must never land here: reconciliation re-adds
            # from it, and an info-hash magnet is both small and re-addable.
            url=raw.strip() if not source.is_blob else f"magnet:?xt=urn:btih:{details.info_hash}",
            info_hash=details.info_hash,
            name=details.name,
            total_size=total_bytes,
            download={"status": DownloadStatus.PENDING, "category_id": category.id, "folder": relative},
            files=[(file.index, file.path, file.size_bytes, file.index in selected) for file in details.files],
        )
        return await self._published(download)

    def _validated_selection(self, details: TorrentDetails, files: Sequence[int]) -> set[int]:
        """The chosen indexes, or every index when nothing was chosen."""
        available = {file.index for file in details.files}
        if not files:
            return available

        unknown = sorted(set(files) - available)
        if unknown:
            raise Error.create(
                code=Code.UNPROCESSABLE_ENTITY,
                message=f"Torrent has no file at index {', '.join(str(i) for i in unknown)}",
                error_type=ErrorType.UNPROCESSABLE_ENTITY,
            )
        return set(files)

    async def _published(self, download: Any) -> DownloadSchema:
        """Serialise, announce, and hand back — as ``DownloadService`` does.

        Publishing here is what makes a torrent appear in every open browser
        the moment it is added, rather than on the monitor's next tick.
        """
        schema = await self._views.one(download, folder=self._relative_folder(download))
        self._hub.publish("download", schema.to_json())
        return schema

    @staticmethod
    def _hash(download: Any) -> str:
        return describe(download).info_hash or ""

    async def pause(self, download_id: uuid.UUID) -> DownloadSchema:
        download = await self._require(download_id)
        if download.status not in (DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.SEEDING):
            raise Error.conflict(message=f"Cannot pause a download that is {download.status.value}")

        await self._client.pause(self._hash(download))
        download.status = DownloadStatus.PAUSED
        await download.save(update_fields=["status"])
        self._live.clear(download.id)
        return await self._published(download)

    async def resume(self, download_id: uuid.UUID) -> DownloadSchema:
        download = await self._require(download_id)
        if download.status not in (DownloadStatus.PAUSED, DownloadStatus.FAILED):
            raise Error.conflict(message=f"Cannot resume a download that is {download.status.value}")

        await self._client.start(self._hash(download))
        # ``downloading`` rather than ``pending``: there is no queue to wait in,
        # the engine is moving bytes the moment it is started. The next monitor
        # tick corrects this to whatever the engine actually reports.
        download.status = DownloadStatus.DOWNLOADING
        download.error = None
        download.error_code = None
        await download.save(update_fields=["status", "error", "error_code"])
        return await self._published(download)

    async def stop_seeding(self, download_id: uuid.UUID) -> DownloadSchema:
        """Stop sharing, keep the files.

        Paused in the engine rather than forgotten, so seeding can be started
        again later without re-adding the magnet. ``complete`` is the status a
        torrent can only reach this way.
        """
        download = await self._require(download_id)
        if download.status != DownloadStatus.SEEDING:
            raise Error.conflict(message=f"Download is {download.status.value}, not seeding")

        await self._client.pause(self._hash(download))
        download.status = DownloadStatus.COMPLETED
        await download.save(update_fields=["status"])
        self._live.clear(download.id)
        return await self._published(download)

    async def cancel(self, download_id: uuid.UUID, *, delete_files: bool = True) -> None:
        """Remove the torrent and the row, with or without the files.

        rqbit draws the distinction for us: ``delete`` takes the data with it,
        ``forget`` drops the torrent and leaves it. An engine that cannot be
        reached does not block either: the person asked for this to be gone.
        """
        download = await self._require(download_id)
        info_hash = self._hash(download)
        try:
            if delete_files:
                await self._client.delete(info_hash)
            else:
                await self._client.forget(info_hash)
        except Error as error:
            logger.warning("{}|engine delete failed for {}: {}", self._tag, download.id, error.message)

        download.status = DownloadStatus.CANCELLED
        download.deleted_at = now()
        await download.save(update_fields=["status", "deleted_at"])
        self._live.clear(download.id)
        await self._published(download)

    def _folder(self, download: Any) -> Path:
        """The torrent's folder on disk, as recorded when it was added (migration 0009 records the older ones)."""
        if not download.folder:
            raise Error.internal(message=f"Torrent {download.id} has no recorded folder")
        return inside(self._downloads, download.folder)

    def _relative_folder(self, download: Any) -> str:
        """The torrent's folder relative to the download root, for its schema."""
        return download.folder or ""

    async def resolve_file(self, download_id: uuid.UUID, index: int) -> tuple[Path, str, str]:
        """One finished file out of a torrent, by its index.

        409 rather than 404 while the torrent is still running: the resource
        will exist, just not yet, and a polling client has to tell "wait" from "never".
        """
        download = await self._require(download_id)
        if download.status not in (DownloadStatus.SEEDING, DownloadStatus.COMPLETED):
            raise Error.conflict(message=f"Download is {download.status.value}, not complete")

        row = await self._file_repo.get(download_id, index)
        if row is None:
            raise Error.not_found(message=f"Torrent has no file at index {index}")
        if not row.selected:
            raise Error.conflict(message=f"File {index} was not selected for download")

        folder = self._folder(download)
        path = (folder / row.path).resolve()
        # A torrent's file names are written by a stranger. Containment is
        # checked against the resolved folder, not by inspecting the string.
        if not path.is_relative_to(folder):
            raise Error.not_found(message="File is not inside the torrent's folder")
        if not path.is_file():
            raise Error.not_found(message="File is no longer on disk")

        media_type, _ = mimetypes.guess_type(path.name)
        return path, path.name, media_type or "application/octet-stream"

    async def media_file_index(self, download_id: uuid.UUID, wanted: int | None = None) -> int:
        """The file Play opens on a torrent: ``wanted``, else its largest selected media file (#94).

        A ``wanted`` file it isn't downloading, or that isn't media, is refused (#95).
        """
        await self._require(download_id)
        media = [
            row
            for row in await self._file_repo.list_for(download_id)
            if row.selected and row.path.lower().endswith(MEDIA_EXTENSIONS)
        ]
        if wanted is not None:
            if any(row.index == wanted for row in media):
                return wanted
            raise Error.create(
                code=Code.UNPROCESSABLE_ENTITY,
                message=f"This torrent isn't downloading a media file at index {wanted}",
                error_type=ErrorType.UNPROCESSABLE_ENTITY,
            )
        if not media:
            raise Error.create(
                code=Code.UNPROCESSABLE_ENTITY,
                message="This torrent has no media file to play",
                error_type=ErrorType.UNPROCESSABLE_ENTITY,
            )
        return max(media, key=lambda row: row.size or 0).index

    async def subtitle_files(
        self, download_id: uuid.UUID, file_index: int | None
    ) -> list[tuple[Sidecar, SidecarSource]]:
        """The subtitle files that go with one of this torrent's videos, and where to read each (#101).

        Whatever was selected for download: subtitle files are small, and a
        torrent often ships the one wanted unselected. One on disk in full is
        read from there; otherwise from rqbit, which streams a file without
        selecting it (#93), for as long as it has the torrent.
        """
        download = await self._require(download_id)
        rows = await self._file_repo.list_for(download_id)
        if file_index is None:
            file_index = await self.media_file_index(download_id)
        video = next((row for row in rows if row.index == file_index), None)
        if video is None:
            return []
        by_path = {row.path: row for row in rows}
        info_hash = self._hash(download)
        in_rqbit = bool(info_hash) and download.status in (
            DownloadStatus.PENDING,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED,
            DownloadStatus.SEEDING,
        )
        folder = self._folder(download)
        found: list[tuple[Sidecar, SidecarSource]] = []
        for sidecar in match_sidecars(video.path, list(by_path)):
            row = by_path[sidecar.path]
            path = (folder / row.path).resolve()
            on_disk = (
                row.selected and path.is_relative_to(folder) and path.is_file() and path.stat().st_size == row.size
            )
            if on_disk:
                found.append((sidecar, path))
            elif in_rqbit:
                found.append((sidecar, TorrentFile(info_hash, row.index)))
        return found

    async def _require(self, download_id: uuid.UUID) -> Any:
        self._require_enabled()
        download = await self._repo.get_active_by_id(download_id)
        if download is None:
            raise Error.not_found(message="Download not found")
        return download

    def _require_enabled(self) -> None:
        if not self._enabled:
            raise Error.service_unavailable(message="Torrent support is disabled")
