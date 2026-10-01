from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Source(LinkBase):
    """A source's stored choice: which parser, on or off, the address it is asked at, and its key."""

    name: str = fields.CharField(max_length=64, unique=True)
    kind: str = fields.CharField(max_length=16)
    enabled: bool = fields.BooleanField(default=True)
    base_url: str = fields.CharField(max_length=2048)
    api_key: str | None = fields.CharField(max_length=1024, null=True)

    def __str__(self) -> str:
        return f"[Source: {self.name}, enabled={self.enabled}]"

    class Meta:
        table: ClassVar[str] = "search_source"
        table_description: ClassVar[str] = "Source"
