from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import Base


class TorrentFile(Base):
    """One file inside a torrent, and whether the user asked for it.

    Selection lives in the database rather than only in the engine because it
    has to survive a restart: startup reconciliation re-adds a torrent rqbit
    has lost, and it can only re-add the right files if it still knows which
    ones they were.

    ``index`` is the file's position in the torrent. That position is the key
    rqbit's ``only_files`` selection and its ``file_progress`` array both use,
    so it is stored rather than derived from row order.

    ``path`` is relative to the task's output folder. ``Task.file_path`` holds
    that folder, which is what keeps this column free of absolute paths.
    """

    task = fields.ForeignKeyField(
        "model.Task", related_name="torrent_files", on_delete=fields.CASCADE
    )
    index: int = fields.IntField()
    path: str = fields.CharField(max_length=1024)
    size_bytes: int = fields.BigIntField(default=0)
    selected: bool = fields.BooleanField(default=True)
    #: Bytes on disk for this file, mirrored from the engine each tick.
    downloaded_bytes: int = fields.BigIntField(default=0)

    def __str__(self) -> str:
        return f"[TorrentFile: task {self.task_id}, index {self.index}, path {self.path}]"

    class Meta:
        table: ClassVar[str] = "torrent_file"
        table_description: ClassVar[str] = "TorrentFile"
        ordering: ClassVar[list[str]] = ["index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("task", "index"),)
