from __future__ import annotations

import uuid
from collections.abc import Sequence

from src.data.db.model import PlaybackPosition
from src.data.repo.download.interface.position import PositionRepo


class PositionDatabaseRepo(PositionRepo):
    async def save(
        self, file_id: uuid.UUID, *, position_seconds: float, duration_seconds: float, watched: bool
    ) -> PlaybackPosition:
        # The player saves every few seconds: one row per file, moved in place.
        row, _ = await PlaybackPosition.update_or_create(
            defaults={"position_seconds": position_seconds, "duration_seconds": duration_seconds, "watched": watched},
            file_id=file_id,
        )
        return row

    async def by_files(self, file_ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, PlaybackPosition]:
        if not file_ids:
            return {}
        return {row.file_id: row for row in await PlaybackPosition.filter(file_id__in=list(file_ids))}
