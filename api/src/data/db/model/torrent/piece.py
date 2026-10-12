from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Piece(LinkBase):
    """One piece of one torrent: its index, size and hash. Which pieces a download has is its ``pieces`` bitmap."""

    torrent = fields.ForeignKeyField("model.Torrent", related_name="pieces", on_delete=fields.CASCADE)
    index: int = fields.IntField()
    size: int = fields.BigIntField()
    hash: str = fields.CharField(max_length=64)

    def __str__(self) -> str:
        return f"[Piece: torrent {self.torrent_id}, index {self.index}]"

    class Meta:
        table: ClassVar[str] = "piece"
        table_description: ClassVar[str] = "Piece"
        schema: ClassVar[str] = "torrent"
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("torrent", "index"),)
