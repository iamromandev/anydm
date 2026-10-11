from __future__ import annotations

from datetime import datetime
from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Speed(LinkBase):
    """The download speed of one UTC hour, kept for 30 days (#362).

    One reading is taken every flush while something is downloading, and an hour
    with no downloads has no row. The sum and the count are stored rather than
    the average, so a new reading is one atomic add; the average is
    ``total_bps / samples``.
    """

    hour: datetime = fields.DatetimeField(unique=True)
    total_bps: int = fields.BigIntField(default=0)
    maximum_bps: int = fields.BigIntField(default=0)
    samples: int = fields.IntField(default=0)

    def __str__(self) -> str:
        return f"[Speed: {self.hour}, {self.samples} samples]"

    class Meta:
        table: ClassVar[str] = "speed"
        table_description: ClassVar[str] = "Speed"
        schema: ClassVar[str] = "audit"
