"""A group's totals, recomputed from its videos (v0.5).

The group row downloads nothing; its status, progress and byte counts are its
videos', written back whenever one of them changes status (never on a progress
tick). Recomputed from the videos each time rather than adjusted, so the totals
can't drift.
"""

from __future__ import annotations

import uuid
from collections.abc import Iterable, Sequence

from src.data.repo.download.interface import TaskRepo
from src.data.schema.download import EntryCountsSchema, TaskSchema
from src.data.type import TaskStatus
from src.lib.event import EventHub

#: Statuses that mean "still to do": queued or downloading.
_ACTIVE = frozenset({TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.MUXING})

EntryRow = tuple[TaskStatus, int, int | None, int]


def group_status(statuses: Iterable[TaskStatus]) -> TaskStatus:
    """Downloading while anything is left to do, then paused, failed or complete."""
    seen = set(statuses)
    if seen & _ACTIVE:
        return TaskStatus.DOWNLOADING
    if TaskStatus.PAUSED in seen:
        return TaskStatus.PAUSED
    if TaskStatus.FAILED in seen:
        return TaskStatus.FAILED
    return TaskStatus.COMPLETE


def counts_of(rows: Sequence[EntryRow]) -> EntryCountsSchema:
    statuses = [row[0] for row in rows]
    return EntryCountsSchema(
        total=len(statuses),
        complete=sum(s in (TaskStatus.COMPLETE, TaskStatus.SEEDING) for s in statuses),
        active=sum(s in _ACTIVE for s in statuses),
        downloading=sum(s in (TaskStatus.DOWNLOADING, TaskStatus.MUXING) for s in statuses),
        paused=statuses.count(TaskStatus.PAUSED),
        failed=statuses.count(TaskStatus.FAILED),
    )


class GroupTotals:
    def __init__(self, repo: TaskRepo, hub: EventHub) -> None:
        self._repo = repo
        self._hub = hub

    async def counts(self, group_id: uuid.UUID) -> EntryCountsSchema:
        return counts_of(await self._repo.entry_statuses(group_id))

    async def refresh(self, group_id: uuid.UUID) -> TaskSchema | None:
        """Write the group's totals from its videos and publish one frame; ``None`` once it's gone."""
        group = await self._repo.get_active_by_id(group_id)
        if group is None:
            return None
        rows = await self._repo.entry_statuses(group_id)
        counts = counts_of(rows)
        known = [total for _, _, total, _ in rows if total is not None]
        group.status = group_status(row[0] for row in rows)
        # Videos done over videos: sizes aren't known until each starts.
        group.progress = counts.complete * 100 // counts.total if counts.total else 0
        group.downloaded_bytes = sum(done for _, done, _, _ in rows)
        group.total_bytes = sum(known) if known else None
        group.speed_bps = sum(speed for _, _, _, speed in rows)
        await group.save(update_fields=["status", "progress", "downloaded_bytes", "total_bytes", "speed_bps"])
        schema = TaskSchema.model_validate(group)
        schema.entry_counts = counts
        self._hub.publish("task", schema.to_json())
        return schema
