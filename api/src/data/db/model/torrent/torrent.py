from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Torrent(LinkBase):
    """A torrent as content: what it's called, its info hash, and how big it is.

    Identified by ``info_hash``, not by where it was found: several ``Source`` rows (a
    magnet, a ``.torrent`` address) point at one ``Torrent``, and so does each
    ``Download`` of it in turn. What describes the content lives here and under it
    (``TorrentFile``, ``Piece``, ``Tracker``); what describes one transfer (peers, how
    many pieces are down) belongs to the ``Download``.
    """

    name: str = fields.CharField(max_length=500)
    info_hash: str = fields.CharField(max_length=64, unique=True)
    total_size: int | None = fields.BigIntField(null=True)

    def __str__(self) -> str:
        return f"[Torrent: {self.name}]"

    class Meta:
        table: ClassVar[str] = "torrent"
        table_description: ClassVar[str] = "Torrent"
        schema: ClassVar[str] = "torrent"


class TorrentFile(LinkBase):
    """One file inside a torrent: its engine index, its path inside the archive and its size.

    ``index`` is the torrent's own file index, the key rqbit's file selection and per-file
    progress use; a download's ``File`` rows point here and carry the same index.
    """

    torrent = fields.ForeignKeyField("model.Torrent", related_name="files", on_delete=fields.CASCADE)
    index: int = fields.IntField()
    path: str = fields.TextField()
    size: int = fields.BigIntField()

    def __str__(self) -> str:
        return f"[TorrentFile: torrent {self.torrent_id}, path {self.path}]"

    class Meta:
        table: ClassVar[str] = "torrent_file"
        table_description: ClassVar[str] = "TorrentFile"
        schema: ClassVar[str] = "torrent"
        ordering: ClassVar[list[str]] = ["index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("torrent", "index"),)
