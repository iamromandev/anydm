from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase

if TYPE_CHECKING:
    from src.data.db.model.play.playback_position import PlaybackPosition


class File(LinkBase):
    """One file of one download: its name, its path, and how big it is.

    A direct or site download has exactly one, at ``index`` 0. A torrent has
    one per file in its metadata, copied when it is added: ``index`` is the
    torrent's own file index, the key rqbit's file selection and per-file
    progress use, and ``selected`` is whether the person chose to fetch it.

    ``path`` is relative to the download's folder (``Show/E01.mp4``), so
    moving the download moves no rows; ``filename`` is its last segment.
    ``mime_type`` is guessed from the name when the row is written.
    """

    download = fields.ForeignKeyField(
        to="model.Download", related_name="files", on_delete=fields.CASCADE
    )
    filename: str = fields.CharField(max_length=512)
    path: str = fields.TextField()
    size: int | None = fields.BigIntField(null=True)
    index: int = fields.IntField()
    downloaded_bytes: int = fields.BigIntField(default=0)
    selected: bool = fields.BooleanField(default=True)
    mime_type: str | None = fields.CharField(max_length=128, null=True)

    if TYPE_CHECKING:
        download_id: UUID
        playback_positions: fields.ReverseRelation[PlaybackPosition]

    def __str__(self) -> str:
        return f"[File: {self.filename}]"

    class Meta:
        table: ClassVar[str] = "file"
        table_description: ClassVar[str] = "File"
        schema: ClassVar[str] = "transfer"
        ordering: ClassVar[list[str]] = ["index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("download", "index"),)
