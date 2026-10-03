from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence
from dataclasses import dataclass

from src.data.db.model import Folder


@dataclass(frozen=True, slots=True)
class FolderRow:
    name: str
    slug: str
    save_dir: str
    extensions: tuple[str, ...]
    position: int


class FolderRepo(ABC):
    @abstractmethod
    async def list_all(self) -> list[Folder]: ...

    @abstractmethod
    async def get(self, folder_id: uuid.UUID) -> Folder | None: ...

    @abstractmethod
    async def other(self) -> Folder:
        """The folder nothing else claims; always seeded."""
        ...

    @abstractmethod
    async def insert_missing(self, rows: Sequence[FolderRow]) -> int:
        """Insert the rows whose name is not taken; never touch one that is. Returns how many."""
        ...
