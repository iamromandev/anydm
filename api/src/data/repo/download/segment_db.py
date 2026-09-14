from __future__ import annotations

import uuid
from collections.abc import Mapping, Sequence

from src.data.db.model import TaskSegment
from src.data.repo.download.interface.segment import Reconciled, TaskSegmentRepo


class TaskSegmentDatabaseRepo(TaskSegmentRepo):
    async def reconcile(self, task_id: uuid.UUID, part: str, plan: Sequence[tuple[int, int, int]]) -> Reconciled:
        rows = await TaskSegment.filter(task_id=task_id, part=part).order_by("index")
        stored = [(row.index, row.start_byte, row.end_byte) for row in rows]

        if stored == list(plan):
            return Reconciled({row.index: row.downloaded for row in rows}, fresh=False)

        if rows:
            await TaskSegment.filter(task_id=task_id, part=part).delete()
        if plan:
            await TaskSegment.bulk_create(
                [
                    TaskSegment(task_id=task_id, part=part, index=index, start_byte=start, end_byte=end)
                    for index, start, end in plan
                ]
            )
        return Reconciled({index: 0 for index, _, _ in plan}, fresh=True)

    async def flush(self, task_id: uuid.UUID, part: str, watermarks: Mapping[int, int]) -> None:
        if not watermarks:
            return
        rows = await TaskSegment.filter(task_id=task_id, part=part, index__in=list(watermarks))
        for row in rows:
            row.downloaded = watermarks[row.index]
        await TaskSegment.bulk_update(rows, fields=["downloaded"])

    async def progress(self, task_id: uuid.UUID, part: str) -> int:
        rows = await TaskSegment.filter(task_id=task_id, part=part)
        return sum(row.downloaded for row in rows)

    async def clear(self, task_id: uuid.UUID, part: str | None = None) -> None:
        query = TaskSegment.filter(task_id=task_id)
        if part is not None:
            query = query.filter(part=part)
        await query.delete()
