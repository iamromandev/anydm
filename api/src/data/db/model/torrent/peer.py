from __future__ import annotations

from datetime import datetime
from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import PeerStatus


class Peer(LinkBase):
    """One peer of one torrent: who it is, whether it connects, and how much it has moved."""

    torrent = fields.ForeignKeyField("model.Torrent", related_name="peers", on_delete=fields.CASCADE)
    address: str = fields.CharField(max_length=255)
    port: int = fields.IntField()
    client: str | None = fields.CharField(max_length=255, null=True)
    status: PeerStatus = fields.CharEnumField(PeerStatus, default=PeerStatus.CONNECTING)
    downloaded_bytes: int = fields.BigIntField(default=0)
    uploaded_bytes: int = fields.BigIntField(default=0)
    last_seen_at: datetime | None = fields.DatetimeField(null=True)

    def __str__(self) -> str:
        return f"[Peer: torrent {self.torrent_id}, {self.address}:{self.port}, status {self.status}]"

    class Meta:
        table: ClassVar[str] = "peer"
        table_description: ClassVar[str] = "Peer"
        schema: ClassVar[str] = "torrent"
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("torrent", "address", "port"),)
