from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Url(LinkBase):
    """One address asked at: the value, its normalized form, and its parts."""

    value: str = fields.TextField()
    normalized: str = fields.TextField()
    scheme: str = fields.CharField(max_length=16)
    host: str | None = fields.CharField(max_length=255, null=True)
    port: int | None = fields.SmallIntField(null=True)
    path: str | None = fields.TextField(null=True)
    query: str | None = fields.TextField(null=True)
    fragment: str | None = fields.TextField(null=True)

    def __str__(self) -> str:
        return f"[Url: {self.value}]"

    class Meta:
        table: ClassVar[str] = "url"
        table_description: ClassVar[str] = "Url"
        schema: ClassVar[str] = "shared"
