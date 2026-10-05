from __future__ import annotations

from datetime import time
from typing import ClassVar

from tortoise import fields
from tortoise.migrations.constraints import UniqueConstraint

from src.core.base import LinkBase


class Queue(LinkBase):
    """A named lane downloads wait in: how many of its downloads run at once, and when it is open.

    Exactly one queue is the default, the one a new download joins: a partial
    unique constraint on ``is_default``. ``CreateModel`` does not apply it, so
    the migration adds it with ``AddConstraint``.
    """

    name: str = fields.CharField(max_length=64, unique=True)
    #: The name made URL- and comparison-safe; what code and the API refer to.
    slug: str = fields.CharField(max_length=64, unique=True)
    #: The queue a new download joins when none is named.
    is_default: bool = fields.BooleanField(default=False)
    #: Stops the whole queue without touching its downloads.
    is_paused: bool = fields.BooleanField(default=False)
    max_concurrent: int = fields.IntField(default=1)
    #: Both null: always open. ``stop_time`` before ``start_time`` crosses midnight.
    start_time: time | None = fields.TimeField(null=True)
    stop_time: time | None = fields.TimeField(null=True)
    #: ISO weekdays, 1 (Monday) to 7; null means every day.
    days: list[int] | None = fields.JSONField(null=True)
    position: int = fields.IntField(default=0)

    def __str__(self) -> str:
        return f"[Queue: {self.slug}]"

    class Meta:
        table: ClassVar[str] = "queue"
        table_description: ClassVar[str] = "Queue"
        schema: ClassVar[str] = "transfer"
        ordering: ClassVar[list[str]] = ["position"]
        constraints: ClassVar[tuple[UniqueConstraint, ...]] = (
            UniqueConstraint(
                fields=("is_default",),
                name="uidx_queue_default",
                condition='"is_default" IS TRUE',
            ),
        )
