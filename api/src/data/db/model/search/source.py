from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class SearchSource(LinkBase):
    """A built-in public source's stored choice: on or off, and the address it is asked at.

    The registry in ``src.lib.sources.registry`` holds what code must know about each
    source; this holds only what a person can change.
    """

    name: str = fields.CharField(max_length=64, unique=True)
    enabled: bool = fields.BooleanField(default=True)
    base_url: str = fields.CharField(max_length=2048)

    def __str__(self) -> str:
        return f"[SearchSource: {self.name}, enabled={self.enabled}]"

    class Meta:
        table: ClassVar[str] = "search_source"
        table_description: ClassVar[str] = "SearchSource"
