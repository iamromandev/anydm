from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class SearchSource(LinkBase):
    """A source's stored choice: which parser, on or off, the address it is asked at, and its key.

    The registry in ``src.lib.sources.registry`` holds what code must know about each
    built-in kind; this holds everything a person can change, for built-ins and Torznab indexers alike.
    """

    name: str = fields.CharField(max_length=64, unique=True)
    kind: str = fields.CharField(max_length=16)
    enabled: bool = fields.BooleanField(default=True)
    base_url: str = fields.CharField(max_length=2048)
    api_key: str | None = fields.CharField(max_length=1024, null=True)

    def __str__(self) -> str:
        return f"[SearchSource: {self.name}, enabled={self.enabled}]"

    class Meta:
        table: ClassVar[str] = "search_source"
        table_description: ClassVar[str] = "SearchSource"
