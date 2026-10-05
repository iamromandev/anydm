from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Url(LinkBase):
    """One address asked at: the value, its normalized form, and its parts.

    ``normalized`` is ``src.core.url.normalize_url(value)`` and
    ``normalized_hash`` is ``url_hash(normalized)``, the same hash as
    ``src.lib.identity.url_ref``. The hash is what makes one address one row: a
    unique index on the text itself would refuse an address longer than a
    B-tree entry allows, and signed links get that long.
    """

    value: str = fields.TextField()
    normalized: str = fields.TextField()
    normalized_hash: str = fields.CharField(max_length=64, unique=True)
    scheme = fields.CharField(max_length=16)
    host = fields.CharField(max_length=255, null=True)
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
