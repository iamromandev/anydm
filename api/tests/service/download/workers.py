"""A ``DownloadWorker`` built from fakes, and the rows it is handed.

``claim_next`` hands the worker a download with its relations loaded; these
rows carry them as plain attributes, and the files live in a ``MemoryFiles``.
"""

from __future__ import annotations

import uuid
from pathlib import Path
from typing import Any, cast

from src.data.repo.download.interface.attempt import USABLE_MIRRORS, Opened
from src.data.type import AttemptStatus, DownloadStatus, MediaKind, MirrorStatus, Platform, Preset
from src.lib.event import EventHub
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker
from src.service.download.live import LiveStats

from tests.service.download.described_rows import a_site_row
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


class FakeAttempts:
    """Tries opened through a download's usable mirrors, as ``AttemptDatabaseRepo`` would, recorded in memory."""

    def __init__(self, *, spare_mirror: bool = False) -> None:
        self.closed: list[AttemptStatus] = []
        self.retired: list[MirrorStatus] = []
        self._spare = spare_mirror

    async def open(self, download: Any) -> Opened | None:
        usable = [mirror for mirror in download.mirrors if mirror.status in USABLE_MIRRORS]
        if not usable:
            return None
        mirror = min(usable, key=lambda mirror: (mirror.priority, mirror.created_at))
        return Opened(uuid.uuid4(), download.id, mirror.id, mirror.source.url.value, download.downloaded_size)

    async def close(self, opened: Opened, status: AttemptStatus) -> None:
        self.closed.append(status)

    async def spare(self, opened: Opened) -> bool:
        return self._spare

    async def retire(self, opened: Opened, status: MirrorStatus) -> None:
        self.retired.append(status)


class FlushRecordingRepo:
    """Records progress flushes. ``person_got_there_first`` refuses every try's outcome, as a
    pause or remove made while the try ran makes ``DownloadDatabaseRepo.end_try`` do."""

    def __init__(self, *, person_got_there_first: bool = False) -> None:
        self.flushed: list[dict[str, Any]] = []
        self.ended: list[dict[str, Any]] = []
        self._refuse = person_got_there_first

    async def end_try(self, download_id: uuid.UUID, fields: Any, *, over: Any = None) -> bool:
        if self._refuse:
            return False
        self.ended.append(dict(fields))
        return True

    async def flush_progress(self, download_id: uuid.UUID, **fields: Any) -> None:
        self.flushed.append(fields)


class FakeCollections:
    """The one collection a collection video's worker looks up."""

    def __init__(self, folder: str | None = None) -> None:
        self.collection: Any = (
            a_site_row(
                "https://www.youtube.com/playlist?list=PL",
                provider="Youtube",
                title=folder,
                kind=MediaKind.PLAYLIST,
                preset=Preset.BEST,
                id=uuid.uuid4(),
            )
            if folder is not None
            else None
        )

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
        "preset": Preset.P1080,
        "video_format": "137",
        "audio_format": "140",
    }
    for key in ("preset", "video_format", "audio_format"):
        if key in overrides:
            detail[key] = overrides.pop(key)
    fields: dict[str, Any] = {
        "source_url": "https://youtu.be/dQw4w9WgXcQ",
        "provider": overrides.pop("extractor", "Youtube"),
        "ref_id": overrides.pop("video_id", "dQw4w9WgXcQ"),
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
    attempts: Any = None,
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
        attempts=cast(Any, attempts or FakeAttempts()),
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
