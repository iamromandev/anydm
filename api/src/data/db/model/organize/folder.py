from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Folder(LinkBase):
    """A place downloads are filed: the directory, and the file types that send a download there."""

    name: str = fields.CharField(max_length=64, unique=True)
    slug: str = fields.CharField(max_length=64, unique=True)
    save_dir: str = fields.CharField(max_length=1024)
    extensions: list[str] = fields.JSONField(default=list)
    position: int = fields.IntField(default=0)

    def __str__(self) -> str:
        return f"[Folder: {self.slug}]"

    class Meta:
        table: ClassVar[str] = "folder"
        table_description: ClassVar[str] = "Folder"
        schema: ClassVar[str] = "organize"
        ordering: ClassVar[list[str]] = ["position"]
