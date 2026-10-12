from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence

from src.data.db.model import Piece

#: ``(index, size, hash)``. A tuple rather than the lib's ``PieceSpec``: the
#: data layer imports nothing from ``src.lib`` or ``src.service``.
PieceRow = tuple[int, int, str]


class PieceRepo(ABC):
    @abstractmethod
    async def ensure(self, download_id: uuid.UUID, pieces: Sequence[PieceRow]) -> None:
        """Give the download's torrent its piece list, unless it has one.

        The list describes the content, so it is written once per torrent: a torrent added
        again, or reached from a second source, keeps the rows it has.
        """
        ...

    @abstractmethod
    async def list_for(self, download_id: uuid.UUID) -> list[Piece]:
        """The torrent's piece rows, in index order."""
        ...

    @abstractmethod
    async def bitmap(self, download_id: uuid.UUID) -> bytes | None:
        """Which pieces the download has, one bit each in rqbit's order; none until it is first saved."""
        ...

    @abstractmethod
    async def save_bitmap(self, download_id: uuid.UUID, bitmap: bytes) -> None:
        """Record which pieces the download has. One write, whatever the number of pieces."""
        ...
