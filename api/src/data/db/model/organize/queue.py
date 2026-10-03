from __future__ import annotations

from datetime import time
from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Queue(LinkBase):
    """A named queue: how many of its downloads run at once, and when it is open."""

    name: str = fields.CharField(max_length=64, unique=True)
    max_concurrent: int = fields.IntField(default=1)
    #: Both null: always open. ``stop_time`` before ``start_time`` crosses midnight.
    start_time: time | None = fields.TimeField(null=True)
    stop_time: time | None = fields.TimeField(null=True)
    #: ISO weekdays, 1 (Monday) to 7; null means every day.
    days: list[int] | None = fields.JSONField(null=True)
    position: int = fields.IntField(default=0)

    def __str__(self) -> str:
        return f"[Queue: {self.name}]"

    class Meta:
        table: ClassVar[str] = "queue"
        table_description: ClassVar[str] = "Queue"
        schema: ClassVar[str] = "organize"
        ordering: ClassVar[list[str]] = ["position"]
