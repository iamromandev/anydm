from __future__ import annotations

from datetime import datetime
from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import AttemptStatus


class Attempt(LinkBase):
    """One try at one Download via one Mirror: running, completed, failed, or cancelled."""

    mirror = fields.ForeignKeyField(
        to="model.Mirror", related_name="attempts", on_delete=fields.CASCADE
    )
    status: AttemptStatus = fields.CharEnumField(
        enum_type=AttemptStatus, default=AttemptStatus.RUNNING
    )
    downloaded_bytes: int = fields.BigIntField(default=0)
    started_at: datetime = fields.DatetimeField(auto_now_add=True)
    completed_at: datetime | None = fields.DatetimeField(null=True)

    def __str__(self) -> str:
        return f"[Attempt: mirror {self.mirror.id}, status {self.status}]"

    class Meta:
        table: ClassVar[str] = "attempt"
        table_description: ClassVar[str] = "Attempt"
        schema: ClassVar[str] = "transfer"
