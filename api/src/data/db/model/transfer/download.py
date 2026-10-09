from __future__ import annotations

from datetime import datetime
from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields
from tortoise.indexes import Index

from src.core.base import Base
from src.data.type import DOWNLOADS_ID, DownloadStatus

if TYPE_CHECKING:
    from src.data.db.model.transfer.media import Media


class Download(Base):
    """One download, from the request that created it to the files it produced.

    Removing one is a soft delete: ``status`` becomes ``CANCELLED`` and
    ``deleted_at`` is set, and the row drops out of every list. Its title,
    platform and media kind are not stored here; they come from what it was
    added from (its source, its torrent, its media).

    ``error``, ``error_code``, ``attempts`` and ``next_attempt_at`` are the
    current retry state, which the worker and the list read. Each try via a
    mirror is an ``Attempt``.
    """

    # A playlist or channel tab is itself a Download (its kind and preset in its
    # media); its videos point at it here.
    #: The collection this download belongs to; none for a standalone download.
    parent = fields.ForeignKeyField("model.Download", related_name="children", null=True, on_delete=fields.CASCADE)
    category = fields.ForeignKeyField(
        "model.Category", related_name="downloads", on_delete=fields.RESTRICT, db_index=True, db_default=DOWNLOADS_ID
    )
    #: Where its files are, relative to DOWNLOAD_DIR, once they have a place. Null on rows from before categories.
    folder: str | None = fields.TextField(null=True)
    status: DownloadStatus = fields.CharEnumField(DownloadStatus, default=DownloadStatus.PENDING, db_index=True)
    total_size: int | None = fields.BigIntField(null=True)
    downloaded_size: int = fields.BigIntField(default=0)
    uploaded_size: int = fields.BigIntField(default=0)
    priority: int = fields.IntField(default=0)
    speed_limit: int | None = fields.BigIntField(null=True)
    started_at: datetime | None = fields.DatetimeField(null=True)
    completed_at: datetime | None = fields.DatetimeField(null=True)
    error: str | None = fields.TextField(null=True)
    error_code: str | None = fields.CharField(max_length=64, null=True)
    attempts: int = fields.IntField(default=0)
    #: When a retryable failure may run again; the row waits in ``PENDING`` until then.
    next_attempt_at: datetime | None = fields.DatetimeField(null=True)

    if TYPE_CHECKING:
        parent_id: UUID | None
        category_id: UUID
        children: fields.ReverseRelation[Download]
        #: A site download's title, kind, preset and formats; none for a torrent or a direct file.
        media: Media | None

    def __str__(self) -> str:
        return f"[Download: id {self.id}, status {self.status}]"

    class Meta:
        table: ClassVar[str] = "download"
        table_description: ClassVar[str] = "Download"
        schema: ClassVar[str] = "transfer"
        indexes: ClassVar[tuple[Index, ...]] = (
            Index(fields=["status", "created_at"], name="idx_download_status_created"),
            Index(fields=["parent_id", "created_at"], name="idx_download_parent_created"),
        )
