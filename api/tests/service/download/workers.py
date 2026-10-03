"""A ``DownloadWorker`` built from fakes, and the rows it is handed.

``claim_next`` hands the worker a download with its relations loaded; these
rows carry them as plain attributes, and the files live in a ``MemoryFiles``.
"""

from __future__ import annotations

import uuid
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

from src.data.type import DownloadStatus, MediaKind, Platform, Preset
from src.lib.event import EventHub
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker
from src.service.download.live import LiveStats

from tests.service.download.memory import MemoryFiles, download_row, memory_views, site_detail


class FakeSegmentRepo:
    def __init__(self) -> None:
        self.flushed: list[tuple[str, dict[int, int]]] = []
        self.cleared: list[uuid.UUID] = []

    async def progress(self, download_id: uuid.UUID, part: Any) -> int:
        return 0

    async def flush(self, download_id: uuid.UUID, part: Any, watermarks: Any) -> None:
        self.flushed.append((str(part), dict(watermarks)))

    async def clear(self, download_id: uuid.UUID, part: Any = None) -> None:
        self.cleared.append(download_id)


class FlushRecordingRepo:
    def __init__(self) -> None:
        self.flushed: list[dict[str, Any]] = []

    async def flush_progress(self, download_id: uuid.UUID, **fields: Any) -> None:
        self.flushed.append(fields)


class FakeCollections:
    """The one collection a collection video's worker looks up."""

    def __init__(self, folder: str | None = None) -> None:
        self.collection: Any = SimpleNamespace(id=uuid.uuid4(), title=folder, ref_id="PL", folder_id=None) if folder is not None else None

    async def get_active_by_id(self, collection_id: uuid.UUID) -> Any:
        if self.collection is not None and collection_id == self.collection.id:
            return self.collection
        return None


class RecordingTotals:
    def __init__(self) -> None:
        self.refreshed: list[uuid.UUID] = []

    async def refresh(self, collection_id: uuid.UUID) -> None:
        self.refreshed.append(collection_id)


async def site_row(files: MemoryFiles, *, filename: str = "Rick_1080p.mp4", **overrides: Any) -> Any:
    """A claimed YouTube download at 1080p: two parts, video and audio."""
    detail: dict[str, Any] = {
        "video_id": "dQw4w9WgXcQ",
        "preset": Preset.P1080,
        "video_format": "137",
        "audio_format": "140",
    }
    for key in ("extractor", "video_id", "preset", "video_format", "audio_format"):
        if key in overrides:
            detail[key] = overrides.pop(key)
    fields: dict[str, Any] = {
        "source_url": "https://youtu.be/dQw4w9WgXcQ",
        "platform": Platform.SITE,
        "media_kind": MediaKind.VIDEO,
        "title": "Rick",
        "status": DownloadStatus.DOWNLOADING,
        "site_detail": site_detail(**detail),
    }
    fields.update(overrides)
    row = download_row(**fields)
    await files.set_single(row.id, path=filename, mime_type=None)
    return row


async def direct_row(files: MemoryFiles, *, filename: str = "big.iso", **overrides: Any) -> Any:
    fields: dict[str, Any] = {
        "source_url": "https://cdn.test/big.iso",
        "platform": Platform.DIRECT,
        "media_kind": MediaKind.FILE,
        "status": DownloadStatus.DOWNLOADING,
    }
    fields.update(overrides)
    row = download_row(**fields)
    await files.set_single(row.id, path=filename, mime_type=None)
    return row


def worker(
    root: Path,
    *,
    files: MemoryFiles | None = None,
    repo: Any = None,
    segment_repo: Any = None,
    collections: Any = None,
    client: Any = None,
    engine: Any = None,
    post: Any = None,
    hub: Any = None,
    live: LiveStats | None = None,
    totals: Any = None,
    disk: Any = None,
    fragments: Any = None,
    segments: int = 4,
) -> DownloadWorker:
    files = files or MemoryFiles()
    live = live or LiveStats()
    stub = cast(Any, object())
    return DownloadWorker(
        name="test",
        repo=cast(Any, repo or FlushRecordingRepo()),
        segment_repo=cast(Any, segment_repo or FakeSegmentRepo()),
        files=cast(Any, files),
        collections=cast(Any, collections or FakeCollections()),
        client=client or stub,
        engine=engine or stub,
        post_processor=post or stub,
        control=DownloadControl(),
        hub=hub or EventHub(),
        downloads_root=root,
        max_attempts=3,
        segments=segments,
        live=live,
        views=memory_views(files=files, live=live),
        totals=totals,
        disk=disk,
        fragments=fragments,
    )
