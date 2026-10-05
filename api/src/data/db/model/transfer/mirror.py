from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import MirrorStatus


class Mirror(LinkBase):
    """One source for one download: retried in priority order until one works."""

    source = fields.ForeignKeyField(to="model.Source", related_name="mirrors", on_delete=fields.CASCADE)
    download = fields.ForeignKeyField(to="model.Download", related_name="mirrors", on_delete=fields.CASCADE)
    priority: int = fields.IntField(default=0)
    status: MirrorStatus = fields.CharEnumField(MirrorStatus, default=MirrorStatus.AVAILABLE)

    def __str__(self) -> str:
        return f"[Mirror: status {self.status}, download {self.download.id}]"

    class Meta:
        table: ClassVar[str] = "mirror"
        table_description: ClassVar[str] = "Mirror"
        schema: ClassVar[str] = "transfer"
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("source", "download"),)
