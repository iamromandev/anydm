"""Status sweeps shared by the download and collection repos."""

from __future__ import annotations

import uuid
from typing import Any, cast

from src.data.db.model import Download
from src.data.type import DownloadStatus


async def ids_of(query: Any, column: str) -> list[uuid.UUID]:
    """One uuid column of ``query``'s rows. Tortoise types a flat ``values_list`` as tuples."""
    return cast(list[uuid.UUID], list(await query.values_list(column, flat=True)))


async def next_queue_position(queue_id: uuid.UUID, conn: Any) -> int:
    """One past the last ``queue_position`` in ``queue_id``: where a new download joins its end."""
    last = cast(
        list[int],
        await Download.filter(queue_id=queue_id)
        .using_db(conn)
        .order_by("-queue_position")
        .limit(1)
        .values_list("queue_position", flat=True),
    )
    return (last[0] + 1) if last else 0


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
