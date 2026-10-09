"""In-memory stand-ins for the download repos, and rows shaped like the models.

Rows are ``SimpleNamespace`` objects carrying every ``Download`` attribute and
async ``save``/``refresh_from_db``/``fetch_related``; ``save`` records the
fields it was asked to write in ``row.saved``.

A row also carries what ``describe`` reads (``mirrors``, ``media``), derived
from the older flat fields (``url``, ``platform``, ``provider``,
``site_detail``) that services not yet moved onto ``describe`` still read; its
``media`` is its ``site_detail``, one object, so a re-plan shows in both.
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence
from types import SimpleNamespace
from typing import Any

from src.data.type import DOWNLOADS_ID, DownloadStatus, MediaKind, Platform, Preset, SourceKind
from src.service.download.live import LiveStats
from src.service.download.views import DownloadViews

from tests.service.download.described_rows import a_mirror, a_torrent

_KIND = {Platform.SITE: SourceKind.CONTENT, Platform.DIRECT: SourceKind.DIRECT, Platform.TORRENT: SourceKind.TORRENT}


def _saving(row: SimpleNamespace) -> SimpleNamespace:
    row.saved = []

    async def save(*_: Any, update_fields: Sequence[str] | None = None, **__: Any) -> None:
        row.saved.append(list(update_fields or []))

    async def nothing(*_: Any, **__: Any) -> None:
        return None

    row.save = save
    row.refresh_from_db = nothing
    row.fetch_related = nothing
    return row


def download_row(**overrides: Any) -> SimpleNamespace:
    fields: dict[str, Any] = dict(
        id=uuid.uuid4(),
        url="https://example.com/a.bin",
        platform=Platform.DIRECT,
        media_kind=MediaKind.FILE,
        title="a.bin",
        provider="http",
        ref_id="x",
        status=DownloadStatus.PENDING,
        folder=None,
        folder_id=None,
        category_id=DOWNLOADS_ID,
        category=SimpleNamespace(id=DOWNLOADS_ID, name="Downloads", folder=""),
        parent_id=None,
        start_at=None,
        download_limit_bps=None,
        checksum_algo=None,
        checksum_expected=None,
        checksum_ok=None,
        total_bytes=None,
        downloaded_bytes=0,
        uploaded_bytes=0,
        error=None,
        error_code=None,
        attempts=0,
        next_attempt_at=None,
        created_at=None,
        started_at=None,
        completed_at=None,
        deleted_at=None,
        site_detail=None,
    )
    fields.update(overrides)
    torrents = [a_torrent(fields["ref_id"], fields["title"])] if fields["platform"] == Platform.TORRENT else []
    fields.setdefault(
        "mirrors", [a_mirror(fields["url"], _KIND[fields["platform"]], fields["provider"], torrents=torrents)]
    )
    media = fields["site_detail"]
    if media is not None and "media" not in fields:
        media.title, media.kind = fields["title"], fields["media_kind"]
        media.playlist_index = getattr(media, "playlist_index", None)
    fields.setdefault("media", media)
    fields.setdefault("total_size", fields["total_bytes"])
    fields.setdefault("downloaded_size", fields["downloaded_bytes"])
    fields.setdefault("uploaded_size", fields["uploaded_bytes"])
    fields.setdefault("speed_limit", fields["download_limit_bps"])
    return _saving(SimpleNamespace(**fields))


def site_detail(**overrides: Any) -> SimpleNamespace:
    fields: dict[str, Any] = dict(preset=Preset.BEST, video_format=None, audio_format=None)
    fields.update(overrides)
    return _saving(SimpleNamespace(**fields))


def file_row(index: int = 0, path: str = "a.bin", **overrides: Any) -> SimpleNamespace:
    fields: dict[str, Any] = dict(
        id=uuid.uuid4(),
        index=index,
        path=path,
        filename=path.rsplit("/", 1)[-1],
        size=0,
        downloaded_bytes=0,
        selected=True,
        mime_type=None,
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


class MemoryFiles:
    def __init__(self) -> None:
        self.by_download: dict[uuid.UUID, list[SimpleNamespace]] = {}
        self.flushed: list[tuple[uuid.UUID, list[int]]] = []

    async def replace(self, download_id: uuid.UUID, files: Sequence[tuple[int, str, int, bool]]) -> None:
        self.by_download[download_id] = [
            file_row(index, path, size=size, selected=selected) for index, path, size, selected in files
        ]

    async def single(self, download_id: uuid.UUID) -> SimpleNamespace | None:
        return await self.get(download_id, 0)

    async def set_single(self, download_id: uuid.UUID, *, path: str, mime_type: str | None) -> SimpleNamespace:
        found = await self.single(download_id)
        if found is None:
            found = file_row(0, path)
            self.by_download.setdefault(download_id, []).append(found)
        found.path, found.mime_type = path, mime_type
        return found

    async def finish_single(self, download_id: uuid.UUID, *, path: str, size_bytes: int) -> None:
        found = await self.single(download_id)
        if found is None:
            found = file_row(0, path)
            self.by_download.setdefault(download_id, []).append(found)
        found.path, found.size, found.downloaded_bytes = path, size_bytes, size_bytes

    async def get(self, download_id: uuid.UUID, index: int) -> SimpleNamespace | None:
        return next((f for f in self.by_download.get(download_id, []) if f.index == index), None)

    async def list_for(self, download_id: uuid.UUID) -> list[SimpleNamespace]:
        return sorted(self.by_download.get(download_id, []), key=lambda f: f.index)

    async def list_for_downloads(self, ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, list[SimpleNamespace]]:
        return {download_id: await self.list_for(download_id) for download_id in ids}

    async def selected_indexes(self, download_id: uuid.UUID) -> list[int]:
        return [f.index for f in await self.list_for(download_id) if f.selected]

    async def flush_progress(self, download_id: uuid.UUID, progress: Sequence[int]) -> None:
        self.flushed.append((download_id, list(progress)))
        for f in self.by_download.get(download_id, []):
            if f.index < len(progress):
                f.downloaded_bytes = int(progress[f.index])


class MemoryPositions:
    def __init__(self) -> None:
        self.by_file: dict[uuid.UUID, SimpleNamespace] = {}

    async def save(
        self, file_id: uuid.UUID, *, position_seconds: float, duration_seconds: float, watched: bool
    ) -> SimpleNamespace:
        row = SimpleNamespace(
            file_id=file_id, position_seconds=position_seconds, duration_seconds=duration_seconds, watched=watched
        )
        self.by_file[file_id] = row
        return row

    async def by_files(self, file_ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, SimpleNamespace]:
        return {i: self.by_file[i] for i in file_ids if i in self.by_file}


class RecordingHub:
    def __init__(self) -> None:
        self.events: list[tuple[str, dict[str, Any]]] = []

    def publish(self, event: str, data: dict[str, Any]) -> None:
        self.events.append((event, data))

    def named(self, event: str) -> list[dict[str, Any]]:
        return [data for name, data in self.events if name == event]


def memory_views(
    *,
    files: MemoryFiles | None = None,
    positions: MemoryPositions | None = None,
    live: LiveStats | None = None,
) -> DownloadViews:
    return DownloadViews(
        files=files or MemoryFiles(),  # ty: ignore[invalid-argument-type]
        positions=positions or MemoryPositions(),  # ty: ignore[invalid-argument-type]
        live=live or LiveStats(),
        max_attempts=3,
    )
