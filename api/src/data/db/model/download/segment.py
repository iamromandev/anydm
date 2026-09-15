from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Segment(LinkBase):
    """One byte range of one part of one task, and how much of it is on disk.

    Not to be confused with ``src.service.download.segment.Segment``, which is
    the transfer engine's in-memory range and carries no identity or progress.
    They never meet: the repository takes ``(index, start, end)`` triples so the
    data layer imports nothing from ``src.service``.

    ``updated_at`` arrives with ``LinkBase`` and tracks the progress flush, which
    rewrites ``downloaded`` about once a second per segment. Nothing reads it —
    liveness checks consult ``Task.heartbeat_at``.

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
        return f"[Segment: task {self.task_id}, part {self.part}, index {self.index}]"

    class Meta:
        table: ClassVar[str] = "segment"
        table_description: ClassVar[str] = "Segment"
        ordering: ClassVar[list[str]] = ["index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("task", "part", "index"),)
