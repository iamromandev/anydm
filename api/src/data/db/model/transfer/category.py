from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Category(LinkBase):
    """A named save folder. A download is in exactly one; Downloads (``builtin``) is the default.

    ``extensions`` and ``mime_prefixes`` are the rules that file a download here by itself (#174).
    ``folder`` is relative to ``DOWNLOAD_DIR``, ``""`` for its root. Changing it
    affects downloads added afterwards: a placed download records its own folder.
    """

    name: str = fields.CharField(max_length=64, unique=True)
    slug: str = fields.CharField(max_length=64, unique=True)
    folder: str = fields.TextField(default="")
    position: int = fields.IntField(default=0)
    builtin: bool = fields.BooleanField(default=False)
    #: What puts a download here when nobody picked a category: lowercase, with the dot (``.mkv``).
    extensions: list[str] = fields.JSONField(default=list, db_default=[])
    #: Content-Type prefixes that do the same (``video/``); an extension is tried first.
    mime_prefixes: list[str] = fields.JSONField(default=list, db_default=[])

    def __str__(self) -> str:
        return f"[Category: {self.name} -> {self.folder or '.'}]"

    class Meta:
        table: ClassVar[str] = "category"
        table_description: ClassVar[str] = "Category"
        schema: ClassVar[str] = "transfer"
        ordering: ClassVar[list[str]] = ["position"]
