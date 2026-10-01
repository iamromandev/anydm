from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase

if TYPE_CHECKING:
    from src.data.db.model.download.position import PlaybackPosition


class DownloadFile(LinkBase):
    """One file of a download. A site or direct download has exactly one, at index 0.

    ``index`` is a torrent's file index, the key rqbit's ``only_files`` and
    ``file_progress`` use, so it is stored rather than derived from row order.
    ``path`` is relative to the download's ``folder``.

    The relation is ``download_files``, never ``files``: a schema field of that
    name read by attribute would be shadowed by Tortoise's reverse manager.
    """

    download = fields.ForeignKeyField("model.Download", related_name="download_files", on_delete=fields.CASCADE)
    index: int = fields.IntField()
    path: str = fields.CharField(max_length=1024)
    size_bytes: int = fields.BigIntField(default=0)
    #: Bytes on disk for this file.
    downloaded_bytes: int = fields.BigIntField(default=0)
    selected: bool = fields.BooleanField(default=True)
    mime_type: str | None = fields.CharField(max_length=128, null=True)

    if TYPE_CHECKING:
        download_id: UUID
        playback: PlaybackPosition | None

    def __str__(self) -> str:
        return f"[DownloadFile: download {self.download_id}, index {self.index}, path {self.path}]"

    class Meta:
        table: ClassVar[str] = "download_file"
        table_description: ClassVar[str] = "DownloadFile"
        ordering: ClassVar[list[str]] = ["index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("download", "index"),)
