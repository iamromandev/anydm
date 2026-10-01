from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence
from dataclasses import dataclass


@dataclass(frozen=True)
class SourceRow:
    """A stored source, as plain values: the data layer imports nothing from ``src.lib`` or ``src.service``."""

    name: str
    kind: str
    enabled: bool
    base_url: str
    api_key: str | None
    #: None until the row is stored, so seeding builds rows without inventing ids.
    id: uuid.UUID | None = None


class SourceRepo(ABC):
    @abstractmethod
    async def list_all(self) -> list[SourceRow]:
        """Every stored row, oldest first."""
        ...

    @abstractmethod
    async def get(self, id: uuid.UUID) -> SourceRow | None: ...

    @abstractmethod
    async def create(self, name: str, kind: str, base_url: str, api_key: str | None, enabled: bool) -> SourceRow: ...

    @abstractmethod
    async def insert_missing(self, rows: Sequence[SourceRow]) -> int:
        """Insert the rows whose name isn't stored; never touch one that is. Returns how many were added."""
        ...

    @abstractmethod
    async def delete(self, id: uuid.UUID) -> bool:
        """Remove the row; ``False`` when there is no such row."""
        ...

    @abstractmethod
    async def update(
        self,
        id: uuid.UUID,
        enabled: bool | None,
        base_url: str | None,
        api_key: str | None = None,
        clear_api_key: bool = False,
    ) -> SourceRow | None:
        """Change what is given; ``None`` when there is no such row.

        The key is only touched when asked: ``api_key`` alone keeps it, ``clear_api_key`` empties it.
        """
        ...
