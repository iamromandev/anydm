"""Moving a download or a collection to another category, with its files when it has any."""

from __future__ import annotations

import contextlib
import uuid
from pathlib import Path
from typing import Any

from src.core.error import Error
from src.data.repo.category import CategoryRepo
from src.data.repo.download.described import describe
from src.data.schema.transfer import CollectionSchema, DownloadSchema
from src.data.type import DownloadStatus, Platform
from src.lib.event import EventHub
from src.service.category import error as category_error
from src.service.category.pick import pick_category
from src.service.download.folders import inside
from src.service.download.paths import container_folder, placed_folder, short_id
from src.service.download.placement import move_dir, move_file_with_sidecars

_RUNNING = frozenset({DownloadStatus.DOWNLOADING, DownloadStatus.MUXING})


class CategoryMover:
    def __init__(
        self,
        *,
        downloads: Any,
        collections: Any,
        categories: CategoryRepo,
        files: Any,
        views: Any,
        totals: Any,
        hub: EventHub,
        downloads_root: Path,
    ) -> None:
        self._downloads = downloads
        self._collections = collections
        self._categories = categories
        self._files = files
        self._views = views
        self._totals = totals
        self._hub = hub
        self._root = downloads_root

    async def move_download(self, download_id: uuid.UUID, category_id: uuid.UUID) -> DownloadSchema:
        download = await self._downloads.get_active_by_id(download_id)
        if download is None:
            raise Error.not_found(message="Download not found")
        if download.parent_id is not None:
            raise category_error.collection_video()
        if describe(download).platform == Platform.TORRENT:
            raise category_error.torrent()
        if download.status in _RUNNING:
            raise category_error.running(download.status.value)
        category = await pick_category(self._categories, category_id)
        if category.id == download.category_id:
            return await self._published(download)

        folder: str | None = None
        if download.status == DownloadStatus.COMPLETED:
            folder = category.folder
            await self._move_file(download, folder)
        await self._downloads.set_category(download.id, category.id, folder)
        download.category_id = category.id
        if folder is not None:
            download.folder = folder
        await download.fetch_related("category")
        return await self._published(download)

    async def move_collection(self, collection_id: uuid.UUID, category_id: uuid.UUID) -> CollectionSchema:
        collection = await self._collections.get_active_by_id(collection_id)
        if collection is None:
            raise Error.not_found(message="Collection not found")
        if any(status in _RUNNING for _, status, _, _ in await self._collections.member_rows(collection_id)):
            raise category_error.collection_running()
        category = await pick_category(self._categories, category_id)
        if category.id == collection.category_id:
            return await self._totals.refresh(collection_id)

        source = inside(self._root, container_folder(collection))
        parent = inside(self._root, category.folder)
        if source.parent == parent:
            # The two categories share a folder: the collection's directory stays where it is.
            folder = container_folder(collection)
        elif source.is_dir():
            folder = self._relative(move_dir(source, parent, short_id(collection.id)))
        else:
            target = parent / source.name
            target.mkdir(parents=True, exist_ok=True)
            folder = self._relative(target)
        await self._collections.move_rows(collection_id, category.id, folder)
        return await self._totals.refresh(collection_id)

    async def _move_file(self, download: Any, folder: str) -> None:
        """Move the finished file into ``folder``. One already there (a crash after the move) is left as it is."""
        file = await self._files.single(download.id)
        if file is None:
            return
        old_dir = inside(self._root, placed_folder(download))
        new_dir = inside(self._root, folder)
        if old_dir == new_dir:
            return
        source = old_dir / Path(file.path).name
        if source.is_file():
            moved = move_file_with_sidecars(source, new_dir, short_id(download.id))
            if moved.name != Path(file.path).name:
                await self._files.rename_single(download.id, moved.name)
            if download.folder is None:
                # The ``<download_id>/`` directory from before categories, now empty.
                with contextlib.suppress(OSError):
                    old_dir.rmdir()
        elif not (new_dir / Path(file.path).name).is_file():
            raise Error.not_found(message="File is no longer on disk")

    def _relative(self, path: Path) -> str:
        return path.relative_to(self._root.resolve()).as_posix()

    async def _published(self, download: Any) -> DownloadSchema:
        schema = await self._views.one(download)
        self._hub.publish("download", schema.to_json())
        return schema
