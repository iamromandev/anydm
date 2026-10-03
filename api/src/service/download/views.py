"""Downloads as the API reports them, built in one place.

Built field by field rather than with ``model_validate``: a download's schema
reads five tables and the live registry, and a relation named like a schema
field once broke every task's validation (see ``DownloadFile``).
"""

from __future__ import annotations

import uuid
from collections.abc import Mapping, Sequence
from dataclasses import asdict
from typing import Any

from src.data.repo.download.interface import FileRepo, MirrorRepo, PositionRepo
from src.data.schema.download import (
    ChecksumSchema,
    DownloadFileSchema,
    DownloadSchema,
    FolderRef,
    LimitsSchema,
    LiveSchema,
    MirrorSchema,
    PlaybackSchema,
    QueueRef,
    SiteSchema,
    TorrentInfoSchema,
)
from src.service.download.live import Live, LiveStats


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
    mirrors: Sequence[Any],
    live: Live,
    max_attempts: int,
) -> DownloadSchema:
    site, torrent = row.site_detail, row.torrent_detail
    folder, queue = row.folder, row.queue
    return DownloadSchema(
        id=row.id,
        source_url=row.source_url,
        platform=row.platform,
        media_kind=row.media_kind,
        title=row.title,
        status=row.status,
        progress=percent(row.downloaded_bytes, row.total_bytes),
        category=FolderRef(id=folder.id, name=folder.name) if folder else None,
        collection_id=row.collection_id,
        position=row.position,
        queue=QueueRef(id=queue.id, name=queue.name) if queue else None,
        queue_position=row.queue_position,
        start_at=row.start_at,
        folder=row.path,
        limits=LimitsSchema(download_bps=row.download_limit_bps),
        checksum=(
            ChecksumSchema(algo=row.checksum_algo, expected=row.checksum_expected or "", ok=row.checksum_ok)
            if row.checksum_algo
            else None
        ),
        total_bytes=row.total_bytes,
        downloaded_bytes=row.downloaded_bytes,
        live=LiveSchema(**asdict(live)),
        site=(
            SiteSchema(
                extractor=site.extractor,
                video_id=site.video_id,
                preset=site.preset,
                video_format=site.video_format,
                audio_format=site.audio_format,
            )
            if site
            else None
        ),
        torrent=(
            TorrentInfoSchema(info_hash=torrent.info_hash, uploaded_bytes=torrent.uploaded_bytes) if torrent else None
        ),
        files=[
            DownloadFileSchema(
                index=file.index,
                path=file.path,
                size_bytes=file.size_bytes,
                downloaded_bytes=file.downloaded_bytes,
                selected=file.selected,
                mime_type=file.mime_type,
                playback=_playback(playback[file.id]) if file.id in playback else None,
            )
            for file in files
        ],
        mirrors=[MirrorSchema(url=m.url, position=m.position, last_error=m.last_error) for m in mirrors],
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
        "downloaded_bytes": downloaded_bytes,
        "progress": percent(downloaded_bytes, total_bytes),
        "live": asdict(live),
    }
    if total_bytes is not None:
        frame["total_bytes"] = total_bytes
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

    def __init__(
        self, files: FileRepo, positions: PositionRepo, mirrors: MirrorRepo, live: LiveStats, max_attempts: int
    ) -> None:
        self._files = files
        self._positions = positions
        self._mirrors = mirrors
        self._live = live
        self._max_attempts = max_attempts

    async def one(self, row: Any) -> DownloadSchema:
        (schema,) = await self.many([row])
        return schema

    async def many(self, rows: Sequence[Any]) -> list[DownloadSchema]:
        ids = [row.id for row in rows]
        files = await self._files.list_for_downloads(ids)
        playback = await self._positions.by_files([file.id for group in files.values() for file in group])
        mirrors = await self._mirrors.list_for_downloads(ids)
        return [
            download_schema(
                row,
                files=files.get(row.id, []),
                playback=playback,
                mirrors=mirrors.get(row.id, []),
                live=self._live.get(row.id),
                max_attempts=self._max_attempts,
            )
            for row in rows
        ]
