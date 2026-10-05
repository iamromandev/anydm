from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Torrent(LinkBase):
    """One source's torrent: what it's called, its info hash, and how big it is."""

    source = fields.ForeignKeyField("model.Source", related_name="torrents", on_delete=fields.CASCADE)
    name: str = fields.CharField(max_length=500)
    info_hash: str = fields.CharField(max_length=64, unique=True)
    total_bytes: int | None = fields.BigIntField(null=True)

    def __str__(self) -> str:
        return f"[Torrent: {self.name}]"

    class Meta:
        table: ClassVar[str] = "torrent"
        table_description: ClassVar[str] = "Torrent"
        schema: ClassVar[str] = "transfer"


class TorrentFile(LinkBase):
    """One file inside a torrent: its path inside the archive and its size."""

    torrent = fields.ForeignKeyField("model.Torrent", related_name="files", on_delete=fields.CASCADE)
    path: str = fields.TextField()
    size: int = fields.BigIntField()

    def __str__(self) -> str:
        return f"[TorrentFile: torrent {self.torrent_id}, path {self.path}]"

    class Meta:
        table: ClassVar[str] = "torrent_file"
        table_description: ClassVar[str] = "TorrentFile"
        schema: ClassVar[str] = "transfer"
