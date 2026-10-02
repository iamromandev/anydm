from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Category(LinkBase):
    """A kind of download, the folder it lands in, and the file types that put a download here."""

    name: str = fields.CharField(max_length=64, unique=True)
    #: Relative to ``DOWNLOAD_DIR``.
    save_dir: str = fields.CharField(max_length=1024)
    #: Lower-case, without the dot. An extension belongs to at most one category.
    extensions: list[str] = fields.JSONField(default=list)
    position: int = fields.IntField(default=0)

    def __str__(self) -> str:
        return f"[Category: {self.name}]"

    class Meta:
        table: ClassVar[str] = "category"
        table_description: ClassVar[str] = "Category"
        ordering: ClassVar[list[str]] = ["position"]
