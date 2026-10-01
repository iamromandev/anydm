from __future__ import annotations

from datetime import datetime
from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class AppFlag(LinkBase):
    """A named one-time step and when it finished; an unset flag means the step is still pending."""

    name: str = fields.CharField(max_length=128, unique=True)
    done_at: datetime = fields.DatetimeField(auto_now_add=True)

    def __str__(self) -> str:
        return f"[AppFlag: {self.name}]"

    class Meta:
        table: ClassVar[str] = "app_flag"
        table_description: ClassVar[str] = "AppFlag"
