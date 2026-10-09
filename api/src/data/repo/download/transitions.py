"""Status sweeps shared by the download and collection repos."""

from __future__ import annotations

import uuid
from typing import Any

from src.data.type import DownloadStatus


async def ids_of(query: Any, column: str) -> list[uuid.UUID]:
    """One uuid column of ``query``'s rows. Tortoise types a flat ``values_list`` as tuples."""
    return list(await query.values_list(column, flat=True))


async def pause_rows(query: Any) -> list[uuid.UUID]:
    """Pause what's queued or downloading in ``query``; the ids that were downloading."""
    running = await ids_of(query.filter(status=DownloadStatus.DOWNLOADING), "id")
    await query.filter(status__in=[DownloadStatus.PENDING, DownloadStatus.DOWNLOADING]).update(
        status=DownloadStatus.PAUSED
    )
    return running


async def requeue_rows(query: Any) -> int:
    """Put the paused and failed in ``query`` back in the queue, as a person's fresh decision."""
    return await query.filter(status__in=[DownloadStatus.PAUSED, DownloadStatus.FAILED]).update(
        status=DownloadStatus.PENDING, attempts=0, error=None, error_code=None, next_attempt_at=None
    )
