from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import TrackerStatus


class Tracker(LinkBase):
    """One tracker of one torrent, which tier it sits in, and whether it answers."""

    torrent = fields.ForeignKeyField("model.Torrent", related_name="trackers", on_delete=fields.CASCADE)
    url: str = fields.TextField()
    tier: int = fields.IntField(default=0)
    status: TrackerStatus = fields.CharEnumField(TrackerStatus, default=TrackerStatus.ACTIVE)

    def __str__(self) -> str:
        return f"[Tracker: torrent {self.torrent_id}, status {self.status}]"

    class Meta:
        table: ClassVar[str] = "tracker"
        table_description: ClassVar[str] = "Tracker"
        schema: ClassVar[str] = "torrent"
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("torrent", "url"),)
