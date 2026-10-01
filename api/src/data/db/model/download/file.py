from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class File(LinkBase):
    """One file inside a torrent, and whether the user asked for it."""

    task = fields.ForeignKeyField(
        "model.Task", related_name="torrent_files", on_delete=fields.CASCADE
    )
    index: int = fields.IntField()
    path: str = fields.CharField(max_length=1024)
    size_bytes: int = fields.BigIntField(default=0)
    selected: bool = fields.BooleanField(default=True)
    downloaded_bytes: int = fields.BigIntField(default=0)

    def __str__(self) -> str:
        return f"[File: task {self.task_id}, index {self.index}, path {self.path}]"

    class Meta:
        table: ClassVar[str] = "file"
        table_description: ClassVar[str] = "File"
        ordering: ClassVar[list[str]] = ["index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("task", "index"),)
