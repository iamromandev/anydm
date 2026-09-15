from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class File(LinkBase):
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

    # ``related_name`` deliberately does not say "files": ``TaskSchema`` has a
    # field of that exact name, and ``TaskSchema.model_validate(task)`` reads
    # attributes by name. A related_name of "files" shadows the intended list
    # with Tortoise's raw ``ReverseRelation`` manager and fails validation for
    # every task, torrent or not — this is what broke the whole worker suite
    # the first time this was tried.
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
        return f"[File: task {self.task_id}, index {self.index}, path {self.path}]"

    class Meta:
        table: ClassVar[str] = "file"
        table_description: ClassVar[str] = "File"
        ordering: ClassVar[list[str]] = ["index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("task", "index"),)
