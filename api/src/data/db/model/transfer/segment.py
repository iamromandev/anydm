from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import SegmentPart


class Segment(LinkBase):
    """One byte range of one part of one download, and how much of it is on disk.

    On the download rather than a file: a site download's parts are its video
    and audio streams before muxing, which are not output files.
    """

    download = fields.ForeignKeyField("model.Download", related_name="segments", on_delete=fields.CASCADE)
    part: SegmentPart = fields.CharEnumField(SegmentPart, max_length=8)
    index: int = fields.IntField()
    start_byte: int = fields.BigIntField()
    end_byte: int = fields.BigIntField()
    #: Bytes durably written, counted from ``start_byte``. Never ahead of disk.
    downloaded: int = fields.BigIntField(default=0)

    if TYPE_CHECKING:
        download_id: UUID

    def __str__(self) -> str:
        return f"[Segment: download {self.download_id}, part {self.part}, index {self.index}]"

    class Meta:
        table: ClassVar[str] = "segment"
        table_description: ClassVar[str] = "Segment"
        schema: ClassVar[str] = "transfer"
        ordering: ClassVar[list[str]] = ["index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("download", "part", "index"),)
