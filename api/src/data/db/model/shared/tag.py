from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Tag(LinkBase):
    """A label a person makes up. It names no resource.

    How a tag attaches to a download, a collection or anything else is a link
    table's job, so this model stays the same whatever it labels.
    """

    name: str = fields.CharField(max_length=64, unique=True)
    #: Any CSS colour the UI accepts, such as ``#d33``; null: the default.
    colour: str | None = fields.CharField(max_length=16, null=True)

    def __str__(self) -> str:
        return f"[Tag: {self.name}]"

    class Meta:
        table: ClassVar[str] = "tag"
        table_description: ClassVar[str] = "Tag"
        schema: ClassVar[str] = "shared"
        ordering: ClassVar[list[str]] = ["name"]
