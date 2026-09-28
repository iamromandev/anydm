from __future__ import annotations

import uuid
from collections.abc import Sequence

from src.data.db.model import PlaybackPosition
from src.data.repo.download.interface.position import PositionRepo


class PositionDatabaseRepo(PositionRepo):
    async def save(
        self,
        task_id: uuid.UUID,
        file_index: int,
        *,
        position_seconds: float,
        duration_seconds: float,
        watched: bool,
    ) -> PlaybackPosition:
        # The player saves every few seconds: one row per task and file, moved in place.
        row, _created = await PlaybackPosition.update_or_create(
            defaults={
                "position_seconds": position_seconds,
                "duration_seconds": duration_seconds,
                "watched": watched,
            },
            task_id=task_id,
            file_index=file_index,
        )
        return row

    async def watched_in_groups(self, group_ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, int]:
        """How many of each group's videos have been watched, in one query (v0.5)."""
        counts: dict[uuid.UUID, int] = dict.fromkeys(group_ids, 0)
        if not counts:
            return counts
        parents = await PlaybackPosition.filter(
            watched=True,
            task__parent_id__in=list(counts),
            task__deleted_at__isnull=True,
        ).values_list("task__parent_id", flat=True)
        for parent_id in parents:
            counts[parent_id] += 1  # ty: ignore[invalid-argument-type]
        return counts

    async def list_for_tasks(
        self, task_ids: Sequence[uuid.UUID]
    ) -> dict[uuid.UUID, list[PlaybackPosition]]:
        by_task: dict[uuid.UUID, list[PlaybackPosition]] = {task_id: [] for task_id in task_ids}
        if not by_task:
            return by_task
        for row in await PlaybackPosition.filter(task_id__in=list(by_task)).order_by("file_index"):
            by_task[row.task_id].append(row)  # ty: ignore[unresolved-attribute]
        return by_task
