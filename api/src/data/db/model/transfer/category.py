from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar

from tortoise import fields

from src.core.base import LinkBase

if TYPE_CHECKING:
    from src.data.db.model.transfer.download import Download


class Category(LinkBase):
    """A named save folder. A download is in exactly one; Downloads (``builtin``) is the default.

    ``folder`` is relative to ``DOWNLOAD_DIR``, ``""`` for its root. Changing it
    affects downloads added afterwards: a placed download records its own folder.
    """

    name: str = fields.CharField(max_length=64, unique=True)
    slug: str = fields.CharField(max_length=64, unique=True)
    folder: str = fields.TextField(default="")
    position: int = fields.IntField(default=0)
    builtin: bool = fields.BooleanField(default=False)

    if TYPE_CHECKING:
        downloads: fields.ReverseRelation[Download]

    def __str__(self) -> str:
        return f"[Category: {self.name} -> {self.folder or '.'}]"

    class Meta:
        table: ClassVar[str] = "category"
        table_description: ClassVar[str] = "Category"
        schema: ClassVar[str] = "transfer"
        ordering: ClassVar[list[str]] = ["position"]
