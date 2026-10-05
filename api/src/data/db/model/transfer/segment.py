from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import SegmentStatus


class Segment(LinkBase):
    """One byte range of one file, how much of it is on disk, and whether it finished."""

    file = fields.ForeignKeyField(to="model.File", related_name="segments", on_delete=fields.CASCADE)
    status: SegmentStatus = fields.CharEnumField(enum_type=SegmentStatus, default=SegmentStatus.PENDING)
    start_byte: int = fields.BigIntField()
    end_byte: int = fields.BigIntField()
    downloaded_bytes: int = fields.BigIntField(default=0)


    def __str__(self) -> str:
        return f"[Segment: file {self.file.id}, {self.start_byte}-{self.end_byte}, status {self.status}]"

    class Meta:
        table: ClassVar[str] = "segment"
        table_description: ClassVar[str] = "Segment"
        schema: ClassVar[str] = "transfer"
