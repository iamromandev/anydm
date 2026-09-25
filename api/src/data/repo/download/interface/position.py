from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence

from src.data.db.model import PlaybackPosition


class PositionRepo(ABC):
    """Where each download was left in the player (#96)."""

    @abstractmethod
    async def save(
        self,
        task_id: uuid.UUID,
        file_index: int,
        *,
        position_seconds: float,
        duration_seconds: float,
        watched: bool,
    ) -> PlaybackPosition:
        """Record a task's file's position, replacing the one it had."""
        ...

    @abstractmethod
    async def list_for_tasks(
        self, task_ids: Sequence[uuid.UUID]
    ) -> dict[uuid.UUID, list[PlaybackPosition]]:
        """Every position of each task, by file, in one query. Every task asked for has a key."""
        ...
