from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class File(LinkBase):
    """One file of one download: its name, its path, and how big it is."""

    download = fields.ForeignKeyField(
        to="model.Download", related_name="files", on_delete=fields.CASCADE
    )
    filename: str = fields.CharField(max_length=512)
    path: str = fields.TextField()
    size: int | None = fields.BigIntField(null=True)

    def __str__(self) -> str:
        return f"[File: {self.filename}]"

    class Meta:
        table: ClassVar[str] = "file"
        table_description: ClassVar[str] = "File"
        schema: ClassVar[str] = "transfer"
