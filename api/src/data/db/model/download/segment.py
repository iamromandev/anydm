from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Segment(LinkBase):
    """One byte range of one part of one task, and how much of it is on disk."""

    task = fields.ForeignKeyField("model.Task", related_name="segments", on_delete=fields.CASCADE)
    part: str = fields.CharField(max_length=16)
    index: int = fields.IntField()
    start_byte: int = fields.BigIntField()
    end_byte: int = fields.BigIntField()
    downloaded: int = fields.BigIntField(default=0)

    def __str__(self) -> str:
        return f"[Segment: task {self.task_id}, part {self.part}, index {self.index}]"

    class Meta:
        table: ClassVar[str] = "segment"
        table_description: ClassVar[str] = "Segment"
        ordering: ClassVar[list[str]] = ["index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("task", "part", "index"),)
