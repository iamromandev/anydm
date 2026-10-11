from __future__ import annotations

import uuid
from collections.abc import Sequence
from typing import Any

from src.data.db.model import Piece, Torrent
from src.data.repo.download.interface.piece import PieceRepo, PieceRow
from src.data.type.torrent.piece import PieceStatus

#: One ``bulk_create`` per this many pieces. A 25,000-piece torrent is dozens
#: of statements at most, and no single statement carries the whole table.
_BATCH = 1000


class PieceDatabaseRepo(PieceRepo):
    async def _torrent_id(self, download_id: uuid.UUID) -> Any:
        torrent = await Torrent.filter(source__mirrors__download_id=download_id).first()
        return torrent.id if torrent is not None else None

    async def replace(self, download_id: uuid.UUID, pieces: Sequence[PieceRow]) -> None:
        torrent_id = await self._torrent_id(download_id)
        if torrent_id is None:
            return
        await Piece.filter(torrent_id=torrent_id).delete()
        rows = [
            Piece(torrent_id=torrent_id, index=index, size=size, hash=digest) for index, size, digest in pieces
        ]
        for start in range(0, len(rows), _BATCH):
            await Piece.bulk_create(rows[start : start + _BATCH])

    async def list_for(self, download_id: uuid.UUID) -> list[Piece]:
        torrent_id = await self._torrent_id(download_id)
        if torrent_id is None:
            return []
        return await Piece.filter(torrent_id=torrent_id).order_by("index")

    async def complete(self, download_id: uuid.UUID, indexes: Sequence[int]) -> None:
        if not indexes:
            return
        torrent_id = await self._torrent_id(download_id)
        if torrent_id is None:
            return
        changed: list[Piece] = []
        for row in await Piece.filter(torrent_id=torrent_id, index__in=list(indexes)):
            if row.status != PieceStatus.COMPLETED:
                row.status = PieceStatus.COMPLETED
                row.downloaded_bytes = row.size
                changed.append(row)
        if changed:
            await Piece.bulk_update(changed, fields=["status", "downloaded_bytes"])
