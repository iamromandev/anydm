from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence
from dataclasses import dataclass

from src.data.db.model import Category


@dataclass(frozen=True, slots=True)
class CategoryRow:
    name: str
    save_dir: str
    extensions: tuple[str, ...]
    position: int


class CategoryRepo(ABC):
    @abstractmethod
    async def list_all(self) -> list[Category]: ...

    @abstractmethod
    async def get(self, category_id: uuid.UUID) -> Category | None: ...

    @abstractmethod
    async def other(self) -> Category:
        """The category nothing else claims; always seeded."""
        ...

    @abstractmethod
    async def insert_missing(self, rows: Sequence[CategoryRow]) -> int:
        """Insert the rows whose name is not taken; never touch one that is. Returns how many."""
        ...
