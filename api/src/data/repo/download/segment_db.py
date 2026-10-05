from __future__ import annotations

import uuid
from collections.abc import Mapping, Sequence

from src.data.db.model import File, Segment
from src.data.repo.download.interface.segment import Reconciled, SegmentRepo
from src.data.type import SegmentPart, SegmentStatus

# A download's segments hang off its one file (index 0), by part. A part's
# ranges carry no index: they are ordered by ``start_byte``, and the engine's
# segment index is a range's place in that order.


def _status(start: int, end: int, downloaded: int) -> SegmentStatus:
    if downloaded <= 0:
        return SegmentStatus.PENDING
    return SegmentStatus.COMPLETED if downloaded >= end - start + 1 else SegmentStatus.DOWNLOADING


def _ranges(download_id: uuid.UUID, part: SegmentPart):
    return Segment.filter(file__download_id=download_id, file__index=0, part=part).order_by("start_byte")


class SegmentDatabaseRepo(SegmentRepo):
    async def reconcile(
        self, download_id: uuid.UUID, part: SegmentPart, plan: Sequence[tuple[int, int, int]]
    ) -> Reconciled:
        rows = await _ranges(download_id, part)
        ordered = sorted(plan, key=lambda planned: planned[1])
        if [(row.start_byte, row.end_byte) for row in rows] == [(start, end) for _, start, end in ordered]:
            return Reconciled(
                {index: row.downloaded_bytes for (index, _, _), row in zip(ordered, rows, strict=True)}, fresh=False
            )

        if rows:
            await Segment.filter(id__in=[row.id for row in rows]).delete()
        if plan:
            file = await File.get(download_id=download_id, index=0)
            await Segment.bulk_create(
                [Segment(file_id=file.id, part=part, start_byte=start, end_byte=end) for _, start, end in ordered]
            )
        return Reconciled({index: 0 for index, _, _ in plan}, fresh=True)

    async def flush(self, download_id: uuid.UUID, part: SegmentPart, watermarks: Mapping[int, int]) -> None:
        if not watermarks:
            return
        rows = await _ranges(download_id, part)
        changed = []
        for index, row in enumerate(rows):
            if index in watermarks:
                row.downloaded_bytes = watermarks[index]
                row.status = _status(row.start_byte, row.end_byte, row.downloaded_bytes)
                changed.append(row)
        if changed:
            await Segment.bulk_update(changed, fields=["downloaded_bytes", "status"])

    async def progress(self, download_id: uuid.UUID, part: SegmentPart) -> int:
        return sum(row.downloaded_bytes for row in await _ranges(download_id, part))

    async def clear(self, download_id: uuid.UUID, part: SegmentPart | None = None) -> None:
        query = Segment.filter(file__download_id=download_id)
        if part is not None:
            query = query.filter(part=part)
        ids = await query.values_list("id", flat=True)
        if ids:
            await Segment.filter(id__in=list(ids)).delete()
