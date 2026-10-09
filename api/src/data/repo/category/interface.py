from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence
from dataclasses import dataclass

from src.data.type import DOWNLOADS_ID


@dataclass(frozen=True)
class CategoryRow:
    id: uuid.UUID
    name: str
    slug: str
    folder: str
    position: int
    builtin: bool


#: The default category as code sees it, without a query.
DOWNLOADS = CategoryRow(DOWNLOADS_ID, "Downloads", "downloads", "", 0, True)


class CategoryRepo(ABC):
    @abstractmethod
    async def list_all(self) -> list[CategoryRow]:
        """Every category, by ``position``."""
        ...

    @abstractmethod
    async def get(self, id: uuid.UUID) -> CategoryRow | None: ...

    @abstractmethod
    async def by_name(self, name: str) -> CategoryRow | None:
        """The category whose name or slug matches, ignoring case."""
        ...

    @abstractmethod
    async def create(self, name: str, slug: str, folder: str) -> CategoryRow:
        """A new category, placed after the last one."""
        ...

    @abstractmethod
    async def update(
        self, id: uuid.UUID, *, name: str | None = None, slug: str | None = None, folder: str | None = None
    ) -> CategoryRow | None:
        """Change what is given; ``None`` when there is no such row."""
        ...

    @abstractmethod
    async def reorder(self, ids: Sequence[uuid.UUID]) -> None:
        """Set every category's ``position`` to its index in ``ids``, in one transaction."""
        ...

    @abstractmethod
    async def delete(self, id: uuid.UUID) -> bool:
        """Remove the row, moving any removed downloads still pointing at it to Downloads first.

        ``False`` when there is no such row. The caller has already refused a
        category that a download in the list still uses.
        """
        ...

    @abstractmethod
    async def usage(self) -> dict[uuid.UUID, int]:
        """How many list items (standalone downloads and collections, not removed) each category holds."""
        ...
