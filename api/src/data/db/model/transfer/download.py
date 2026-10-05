from __future__ import annotations

from datetime import datetime
from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import DownloadStatus, Folder


class Download(LinkBase):
    """One download, from the request that created it to the files it produced."""

    folder: Folder = fields.CharEnumField(enum_type=Folder, default=Folder.DOWNLOADS)
    status: DownloadStatus = fields.CharEnumField(DownloadStatus, default=DownloadStatus.PENDING)
    total_size: int | None = fields.BigIntField(null=True)
    downloaded_size: int = fields.BigIntField(default=0)
    uploaded_size: int = fields.BigIntField(default=0)
    priority: int = fields.IntField(default=0)
    speed_limit: int | None = fields.BigIntField(null=True)
    started_at: datetime | None = fields.DatetimeField(null=True)
    completed_at: datetime | None = fields.DatetimeField(null=True)

    def __str__(self) -> str:
        return f"[Download: id {self.id}, status {self.status}]"

    class Meta:
        table: ClassVar[str] = "download"
        table_description: ClassVar[str] = "Download"
        schema: ClassVar[str] = "transfer"
