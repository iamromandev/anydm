from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence

from src.data.db.model import PlaybackPosition


class PositionRepo(ABC):
    """Where each file was left in the player (#96)."""

    @abstractmethod
    async def save(
        self, file_id: uuid.UUID, *, position_seconds: float, duration_seconds: float, watched: bool
    ) -> PlaybackPosition:
        """Record a file's position, replacing the one it had."""
        ...

    @abstractmethod
    async def by_files(self, file_ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, PlaybackPosition]:
        """The position of each file that has one, by file id, in one query."""
        ...
