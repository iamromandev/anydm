from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence
from dataclasses import dataclass

from src.data.db.model import Queue


@dataclass(frozen=True, slots=True)
class QueueRow:
    name: str
    slug: str
    max_concurrent: int
    position: int
    is_default: bool = False


class QueueRepo(ABC):
    @abstractmethod
    async def list_all(self) -> list[Queue]: ...

    @abstractmethod
    async def get(self, queue_id: uuid.UUID) -> Queue | None: ...

    @abstractmethod
    async def main(self) -> Queue:
        """The default queue, where every download starts; always seeded."""
        ...

    @abstractmethod
    async def insert_missing(self, rows: Sequence[QueueRow]) -> int:
        """Insert the rows whose slug is not taken; never touch one that is. Returns how many."""
        ...
