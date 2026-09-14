from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import StampBase


class TaskSegment(StampBase):
    """One byte range of one part of one task, and how much of it is on disk.

    ``StampBase`` rather than ``Base``: the flush rewrites ``downloaded`` once a
    second per segment, and an ``auto_now`` indexed write on every one of those
    buys nothing that ``Task.heartbeat_at`` does not already say.

    ``start_byte``/``end_byte`` rather than ``start``/``end`` because ``END`` is
    a reserved SQL keyword — the ORM would quote it, and the first hand-written
    query would not.
    """

    task = fields.ForeignKeyField("model.Task", related_name="segments", on_delete=fields.CASCADE)
    part: str = fields.CharField(max_length=16)
    index: int = fields.IntField()
    start_byte: int = fields.BigIntField()
    end_byte: int = fields.BigIntField()
    #: Bytes durably written, counted from ``start_byte``. Never ahead of disk.
    downloaded: int = fields.BigIntField(default=0)

    def __str__(self) -> str:
        return f"[TaskSegment: task {self.task_id}, part {self.part}, index {self.index}]"

    class Meta:
        table: ClassVar[str] = "task_segment"
        table_description: ClassVar[str] = "Task segment"
        ordering: ClassVar[list[str]] = ["index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("task", "part", "index"),)
