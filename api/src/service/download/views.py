"""Downloads as the API reports them, built in one place.

Built field by field rather than with ``model_validate``: a download's schema
reads its row, what ``describe`` derives from its mirror, source and media, its
files and the live registry, and a relation named like a schema field once
broke every task's validation.
"""

from __future__ import annotations

import uuid
from collections.abc import Mapping, Sequence
from dataclasses import asdict
from typing import Any

from src.data.repo.download.described import describe
from src.data.repo.download.interface import FileRepo, PositionRepo
from src.data.schema.download import (
    DownloadFileSchema,
    DownloadSchema,
    LimitsSchema,
    LiveSchema,
    PlaybackSchema,
    SiteSchema,
    TorrentInfoSchema,
)
from src.data.type import CONTAINER_KINDS, DownloadStatus, Platform
from src.service.download.live import Live, LiveStats
from src.service.download.paths import collection_folder, standalone_folder


def percent(downloaded: int, total: int | None) -> int:
    """Whole percent, clamped; zero while the size is unknown."""
    if not total or total <= 0:
        return 0
    return max(0, min(100, downloaded * 100 // total))


def _playback(position: Any) -> PlaybackSchema:
    return PlaybackSchema(
        position_seconds=position.position_seconds,
        duration_seconds=position.duration_seconds,
        watched=position.watched,
    )


def download_schema(
    row: Any,
    *,
    files: Sequence[Any],
    playback: Mapping[uuid.UUID, Any],
    live: Live,
    max_attempts: int,
    folder: str | None = None,
) -> DownloadSchema:
    described = describe(row, files)
    media = described.media
    if folder is None and row.status == DownloadStatus.COMPLETED and row.parent_id is None:
        if described.media_kind in CONTAINER_KINDS:
            folder = collection_folder(described.title, described.ref)
        elif described.platform != Platform.TORRENT:
            folder = standalone_folder(row.id)
        # Torrents resolve via their service, which knows the download root.
    return DownloadSchema(
        id=row.id,
        source_url=described.source_url,
        platform=described.platform,
        media_kind=described.media_kind,
        title=described.title,
        status=row.status,
        progress=percent(row.downloaded_size, row.total_size),
        collection_id=row.parent_id,
        folder=folder,
        limits=LimitsSchema(download_bps=row.speed_limit),
        total_size=row.total_size,
        downloaded_size=row.downloaded_size,
        live=LiveSchema(**asdict(live)),
        site=(
            SiteSchema(
                extractor=described.provider,
                video_id=described.ref,
                preset=media.preset,
                video_format=media.video_format,
                audio_format=media.audio_format,
            )
            if media is not None
            else None
        ),
        torrent=(
            TorrentInfoSchema(info_hash=described.info_hash, uploaded_bytes=row.uploaded_size)
            if described.info_hash is not None
            else None
        ),
        files=[
            DownloadFileSchema(
                index=file.index,
                path=file.path,
                size=file.size or 0,
                downloaded_bytes=file.downloaded_bytes,
                selected=file.selected,
                mime_type=file.mime_type,
                playback=_playback(playback[file.id]) if file.id in playback else None,
            )
            for file in files
        ],
        error=row.error,
        error_code=row.error_code,
        attempts=row.attempts,
        max_attempts=max_attempts,
        next_attempt_at=row.next_attempt_at,
        created_at=row.created_at,
        started_at=row.started_at,
        completed_at=row.completed_at,
    )


def progress_frame(
    download_id: uuid.UUID,
    *,
    collection_id: uuid.UUID | None,
    downloaded_bytes: int,
    total_bytes: int | None,
    live: Live,
    segments: Sequence[Any] | None = None,
    files: Sequence[tuple[int, int]] | None = None,
) -> dict[str, Any]:
    """The light frame: only what moves. Absent keys mean "unchanged" or "not applicable".

    A separate event from the full snapshot: this fires every flush interval
    per download, and the browser only needs the numbers that moved.
    """
    frame: dict[str, Any] = {
        "id": str(download_id),
        "downloaded_size": downloaded_bytes,
        "progress": percent(downloaded_bytes, total_bytes),
        "live": asdict(live),
    }
    if total_bytes is not None:
        frame["total_size"] = total_bytes
    # A collection's video: the browser folds it into the collection's row.
    if collection_id is not None:
        frame["collection_id"] = str(collection_id)
    # Absent rather than empty when the transfer is not segmented: an empty array
    # is a third case the browser would have to tell apart.
    if segments:
        frame["segments"] = [
            {
                "index": segment.index,
                "start": segment.start,
                "end": segment.end,
                "downloaded": segment.downloaded,
                "speed_bps": segment.speed_bps,
            }
            for segment in segments
        ]
    if files is not None:
        frame["files"] = [{"index": index, "downloaded_bytes": done} for index, done in files]
    return frame


class DownloadViews:
    """Schemas for one download or a page of them, with every related read batched."""

    def __init__(self, files: FileRepo, positions: PositionRepo, live: LiveStats, max_attempts: int) -> None:
        self._files = files
        self._positions = positions
        self._live = live
        self._max_attempts = max_attempts

    async def one(self, row: Any, *, folder: str | None = None) -> DownloadSchema:
        (schema,) = await self.many([row], folders={row.id: folder} if folder is not None else None)
        return schema

    async def many(
        self, rows: Sequence[Any], *, folders: Mapping[uuid.UUID, str | None] | None = None
    ) -> list[DownloadSchema]:
        ids = [row.id for row in rows]
        files = await self._files.list_for_downloads(ids)
        playback = await self._positions.by_files([file.id for group in files.values() for file in group])
        return [
            download_schema(
                row,
                files=files.get(row.id, []),
                playback=playback,
                live=self._live.get(row.id),
                max_attempts=self._max_attempts,
                folder=(folders or {}).get(row.id),
            )
            for row in rows
        ]
