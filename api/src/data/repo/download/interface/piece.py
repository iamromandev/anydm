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
    async def replace(self, download_id: uuid.UUID, pieces: Sequence[PieceRow]) -> None:
        """Make the stored piece list exactly ``pieces``.

        Delete then insert, as ``FileRepo.replace``: the list is written once
        when the torrent is added, and this cannot leave a stale row behind.
        """
        ...

    @abstractmethod
    async def list_for(self, download_id: uuid.UUID) -> list[Piece]:
        """Every piece row, in index order."""
        ...

    @abstractmethod
    async def complete(self, download_id: uuid.UUID, indexes: Sequence[int]) -> None:
        """Mark ``indexes`` completed, counting each piece's full size as downloaded.

        One batched write for the whole set: this runs every monitor tick, and
        a write per piece per tick would be the database absorbing the full
        transfer rate.
        """
        ...
