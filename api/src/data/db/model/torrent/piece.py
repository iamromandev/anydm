from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import PieceStatus


class Piece(LinkBase):
    """One piece of one torrent: its index, size, hash, and how much is on disk."""

    torrent = fields.ForeignKeyField("model.Torrent", related_name="pieces", on_delete=fields.CASCADE)
    index: int = fields.IntField()
    size: int = fields.BigIntField()
    hash: str = fields.CharField(max_length=64)
    status: PieceStatus = fields.CharEnumField(PieceStatus, default=PieceStatus.PENDING)
    downloaded_bytes: int = fields.BigIntField(default=0)

    def __str__(self) -> str:
        return f"[Piece: torrent {self.torrent_id}, index {self.index}, status {self.status}]"

    class Meta:
        table: ClassVar[str] = "piece"
        table_description: ClassVar[str] = "Piece"
        schema: ClassVar[str] = "torrent"
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("torrent", "index"),)
