from __future__ import annotations

import uuid
from collections.abc import Mapping, Sequence

from src.data.db.model import Segment
from src.data.repo.download.interface.segment import Reconciled, SegmentRepo
from src.data.type import SegmentPart


class SegmentDatabaseRepo(SegmentRepo):
    async def reconcile(
        self, download_id: uuid.UUID, part: SegmentPart, plan: Sequence[tuple[int, int, int]]
    ) -> Reconciled:
        rows = await Segment.filter(download_id=download_id, part=part).order_by("index")
        stored = [(row.index, row.start_byte, row.end_byte) for row in rows]

        if stored == list(plan):
            return Reconciled({row.index: row.downloaded for row in rows}, fresh=False)

        if rows:
            await Segment.filter(download_id=download_id, part=part).delete()
        if plan:
            await Segment.bulk_create(
                [
                    Segment(download_id=download_id, part=part, index=index, start_byte=start, end_byte=end)
                    for index, start, end in plan
                ]
            )
        return Reconciled({index: 0 for index, _, _ in plan}, fresh=True)

    async def flush(self, download_id: uuid.UUID, part: SegmentPart, watermarks: Mapping[int, int]) -> None:
        if not watermarks:
            return
        rows = await Segment.filter(download_id=download_id, part=part, index__in=list(watermarks))
        for row in rows:
            row.downloaded = watermarks[row.index]
        await Segment.bulk_update(rows, fields=["downloaded"])

    async def progress(self, download_id: uuid.UUID, part: SegmentPart) -> int:
        rows = await Segment.filter(download_id=download_id, part=part)
        return sum(row.downloaded for row in rows)

    async def clear(self, download_id: uuid.UUID, part: SegmentPart | None = None) -> None:
        query = Segment.filter(download_id=download_id)
        if part is not None:
            query = query.filter(part=part)
        await query.delete()
