from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Sequence
from dataclasses import dataclass


@dataclass(frozen=True)
class SearchSourceRow:
    """A stored source, as plain values: the data layer imports nothing from ``src.lib`` or ``src.service``."""

    name: str
    enabled: bool
    base_url: str


class SearchSourceRepo(ABC):
    @abstractmethod
    async def list_all(self) -> list[SearchSourceRow]:
        """Every stored row, oldest first."""
        ...

    @abstractmethod
    async def get(self, name: str) -> SearchSourceRow | None: ...

    @abstractmethod
    async def insert_missing(self, rows: Sequence[SearchSourceRow]) -> int:
        """Insert the rows whose name isn't stored; never touch one that is. Returns how many were added."""
        ...

    @abstractmethod
    async def update(self, name: str, enabled: bool | None, base_url: str | None) -> SearchSourceRow | None:
        """Change what is given; ``None`` when there is no such row."""
        ...
