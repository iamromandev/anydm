from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import SegmentPart, SegmentStatus


class Segment(LinkBase):
    """One byte range of one stream of one file, how much of it is on disk, and whether it finished.

    A site download fetches its video and audio as separate streams before
    muxing them into its one file; ``part`` says which stream a range is of.
    A part's ranges are ordered by ``start_byte``.
    """

    file = fields.ForeignKeyField(to="model.File", related_name="segments", on_delete=fields.CASCADE)
    #: Which stream of the file: the whole file, or a site download's video or audio.
    part: SegmentPart = fields.CharEnumField(enum_type=SegmentPart, default=SegmentPart.FILE)
    status: SegmentStatus = fields.CharEnumField(enum_type=SegmentStatus, default=SegmentStatus.PENDING)
    start_byte: int = fields.BigIntField()
    end_byte: int = fields.BigIntField()
    downloaded_bytes: int = fields.BigIntField(default=0)

    if TYPE_CHECKING:
        file_id: UUID

    def __str__(self) -> str:
        return f"[Segment: file {self.file_id}, {self.part} {self.start_byte}-{self.end_byte}, status {self.status}]"

    class Meta:
        table: ClassVar[str] = "segment"
        table_description: ClassVar[str] = "Segment"
        schema: ClassVar[str] = "transfer"
