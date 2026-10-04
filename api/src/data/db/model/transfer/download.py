from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import Base
from src.data.type import DownloadStatus


class Download(Base):
    """One download, from the request that created it to the files it produced."""

    parent = fields.ForeignKeyField(to="model.Download", related_name="children", null=True, on_delete=fields.CASCADE)
    queue = fields.ForeignKeyField(to="model.Queue", related_name="downloads", on_delete=fields.RESTRICT)
    provider = fields.ForeignKeyField(
        to="model.Provider", related_name="downloads", null=True, on_delete=fields.SET_NULL
    )
    url = fields.ForeignKeyField(to="model.Url", related_name="downloads", null=True, on_delete=fields.SET_NULL)
    status: DownloadStatus = fields.CharEnumField(DownloadStatus, max_length=16, db_index=True)

    # progress
    total_bytes: int | None = fields.BigIntField(null=True)
    downloaded_bytes: int = fields.BigIntField(default=0)

    def __str__(self) -> str:
        return f"[Download: id {self.id},  status {self.status}]"

    class Meta:
        table: ClassVar[str] = "download"
        table_description: ClassVar[str] = "Download"
        schema: ClassVar[str] = "transfer"
        ordering: ClassVar[list[str]] = ["-created_at"]
