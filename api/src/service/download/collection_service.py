"""Playlists and channel tabs, as collections of site downloads (v0.5).

Split out of ``DownloadService``: a collection has its own rows, routes and
lifecycle now, and the old service carried both at 650 lines.
"""

from __future__ import annotations

import asyncio
import shutil
import uuid
from pathlib import Path
from typing import Any

from src.core.base import BaseService
from src.core.error import Error
from src.core.success import Meta
from src.data.repo.download.interface import CollectionRepo, EntryRow, SegmentRepo
from src.data.schema.download import CollectionEntryRequest, CollectionRequest, CollectionSchema, DownloadSchema
from src.data.type import DownloadStatus, MediaKind, Preset
from src.lib.identity import site_ref
from src.lib.site import error as site_error
from src.lib.site.filename import number_prefix
from src.service.download.collection_totals import CollectionTotals
from src.service.download.control import DownloadControl
from src.service.download.disk import DiskGuard
from src.service.download.folders import inside
from src.service.download.paths import collection_relpath, container_folder, remove_work_files
from src.service.download.views import DownloadViews

#: The most videos one add takes; the picker stops there too.
PLAYLIST_LIMIT = 10_000


def remove_collection_video_files(video: Path) -> None:
    """A collection video's file and the subtitle files beside it, never its folder.

    Its subtitles share its stem: ``02_Talk_720p.en.vtt`` beside ``02_Talk_720p.mp4``.
    """
    if not video.parent.is_dir():
        return
    prefix = f"{video.stem}."
    for path in video.parent.iterdir():
        if path == video or (path.name.startswith(prefix) and path.suffix in (".vtt", ".srt")):
            path.unlink(missing_ok=True)


class CollectionService(BaseService):
    def __init__(
        self,
        repo: CollectionRepo,
        segment_repo: SegmentRepo | None,
        control: DownloadControl,
        downloads_root: Path,
        totals: CollectionTotals,
        views: DownloadViews,
        disk: DiskGuard | None = None,
    ) -> None:
        super().__init__()
        self._repo = repo
        self._segment_repo = segment_repo
        self._control = control
        self._root = downloads_root
        self._totals = totals
        self._views = views
        self._disk = disk

    async def add(self, request: CollectionRequest) -> CollectionSchema:
        """The chosen videos as a new collection, or joined to the one already added from this listing.

        Nothing is extracted here: 5,000 videos cannot be planned inside one
        request. A video that turns out private, or without a format for the
        preset, fails inside the collection rather than at add time.
        """
        if len(request.entries) > PLAYLIST_LIMIT:
            raise site_error.playlist_too_large(len(request.entries))
        if self._disk is not None:
            self._disk.require(0)
        # A listing's extractor is "YoutubeTab"; its videos, and so its container, are "Youtube".
        provider = request.extractor.removesuffix("Tab")
        existing = await self._repo.find(request.url)
        if existing is not None:
            return await self._join(existing, request)
        # Named by the id read off its address, as every later read of its folder does
        # (a channel tab's address gives its handle, where the listing gives its UC id).
        path = collection_relpath(None, request.title, site_ref(provider, request.url))
        inside(self._root, path).mkdir(parents=True, exist_ok=True)
        largest = max(entry.index for entry in request.entries)
        collection = await self._repo.create_with_entries(
            url=request.url,
            provider=provider,
            collection={"status": DownloadStatus.PENDING},
            media={
                "kind": MediaKind.CHANNEL if request.channel_tab else MediaKind.PLAYLIST,
                "title": request.title,
                "preset": request.preset,
            },
            entries=[self._entry(entry, request, largest=largest) for entry in request.entries],
        )
        self._control.wake()
        return await self._changed(collection)

    @staticmethod
    def _entry(
        entry: CollectionEntryRequest, request: CollectionRequest, *, largest: int, number: int | None = None
    ) -> EntryRow:
        """One video's rows, unplanned: its formats and name are chosen when it starts."""
        # The number now; planning appends the name. Channel tabs aren't numbered.
        # A re-add numbers by arrival (``number``), so no two files share a prefix.
        prefix = "" if request.channel_tab else number_prefix(number if number is not None else entry.index, largest)
        return EntryRow(
            # The listing's own address for the video, its canonical page: what a
            # single download of it is stored under too.
            url=entry.url,
            download={"status": DownloadStatus.PENDING},
            media={
                "title": entry.title or "",
                "kind": MediaKind.AUDIO if request.preset == Preset.MP3 else MediaKind.VIDEO,
                "preset": request.preset,
                "playlist_index": entry.index,
            },
            filename=prefix,
        )

    async def _join(self, collection: Any, request: CollectionRequest) -> CollectionSchema:
        """The same list added again: its new videos join, in its folder (part 3).

        New videos take the next numbers in this listing's order: a channel's
        tab lists newest first, so its numbers shift with every upload.
        A video already held isn't added twice; ticked while paused or failed,
        it goes back in the queue.
        """
        held = await self._repo.held(collection.id)
        fresh = [entry for entry in request.entries if entry.id not in held]
        if len(held) + len(fresh) > PLAYLIST_LIMIT:
            raise site_error.playlist_too_large(len(held) + len(fresh))
        again = [
            held[entry.id][0]
            for entry in request.entries
            if entry.id in held and held[entry.id][1] in (DownloadStatus.PAUSED, DownloadStatus.FAILED)
        ]
        # Numbered by arrival: the next videos take the next numbers, so no two files share a prefix.
        top = len(held)
        largest = top + len(fresh)
        # Removed by hand since the first add: the videos still finish into it.
        inside(self._root, container_folder(collection)).mkdir(parents=True, exist_ok=True)
        if fresh:
            await self._repo.add_entries(
                collection,
                request.extractor.removesuffix("Tab"),
                [
                    self._entry(entry, request, largest=largest, number=top + offset)
                    for offset, entry in enumerate(fresh, start=1)
                ],
            )
        await self._repo.requeue(again)
        self._control.wake()
        return await self._changed(collection)

    async def get(self, collection_id: uuid.UUID) -> CollectionSchema:
        (schema,) = await self._totals.schemas([await self._require(collection_id)])
        return schema

    async def downloads_page(
        self, collection_id: uuid.UUID, page: int, page_size: int
    ) -> tuple[list[DownloadSchema], Meta]:
        """One page of a collection's videos, in the order they were added."""
        collection = await self._require(collection_id)
        rows, meta = await self._repo.downloads_page(collection_id, page, page_size)
        folder = container_folder(collection)
        return await self._views.many(rows, folders={row.id: folder for row in rows}), meta

    async def pause(self, collection_id: uuid.UUID) -> CollectionSchema:
        collection = await self._require(collection_id)
        for running in await self._repo.pause(collection_id):
            self._control.request_stop(running)
        return await self._changed(collection)

    async def resume(self, collection_id: uuid.UUID) -> CollectionSchema:
        collection = await self._require(collection_id)
        await self._repo.resume(collection_id)
        self._control.wake()
        return await self._changed(collection)

    async def cancel(self, collection_id: uuid.UUID, *, delete_files: bool) -> None:
        """Remove a collection and all of its videos.

        Keeping the files is accepted in any state: what finished is whole, and
        the unfinished videos' work folders go either way.
        """
        collection = await self._require(collection_id)
        for video in await self._repo.remove(collection_id):
            self._control.request_stop(video)
            remove_work_files(self._root, video)
        if delete_files:
            # A folder of 5,000 files shouldn't hold up the API.
            folder = container_folder(collection)
            await asyncio.to_thread(shutil.rmtree, inside(self._root, folder), ignore_errors=True)
        await self._repo.soft_delete(collection)

    async def _changed(self, collection: Any) -> CollectionSchema:
        schema = await self._totals.refresh(collection.id)
        if schema is None:
            raise Error.not_found(message="Collection not found")
        return schema

    async def _require(self, collection_id: uuid.UUID) -> Any:
        collection = await self._repo.get_active_by_id(collection_id)
        if collection is None:
            raise Error.not_found(message="Collection not found")
        return collection
