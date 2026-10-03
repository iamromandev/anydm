from __future__ import annotations

from datetime import datetime
from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields
from tortoise.indexes import Index

from src.core.base import Base
from src.data.type import ChecksumAlgo, DownloadStatus, MediaKind, Platform

if TYPE_CHECKING:
    from src.data.db.model.detail.site_detail import SiteDetail
    from src.data.db.model.detail.torrent_detail import TorrentDetail
    from src.data.db.model.file.download_file import DownloadFile
    from src.data.db.model.transfer.mirror import Mirror
    from src.data.db.model.transfer.segment import Segment


class Download(Base):
    """One download, from the request that created it to the files it produced.

    What only a site download or only a torrent needs lives in ``SiteDetail`` or
    ``TorrentDetail``; the files, even a single one, are ``DownloadFile`` rows.
    Live numbers (speed, ETA, peers) are never stored: ``LiveStats`` holds them.
    """

    source_url: str = fields.TextField()
    platform: Platform = fields.CharEnumField(Platform, max_length=16, db_index=True)
    media_kind: MediaKind = fields.CharEnumField(MediaKind, max_length=8)
    title: str = fields.CharField(max_length=512, default="")
    status: DownloadStatus = fields.CharEnumField(DownloadStatus, max_length=16, db_index=True)

    # organization
    category = fields.ForeignKeyField(
        "model.Category", related_name="downloads", null=True, on_delete=fields.SET_NULL
    )
    collection = fields.ForeignKeyField(
        "model.Collection", related_name="downloads", null=True, on_delete=fields.CASCADE
    )
    #: Its number in the collection's listing.
    position: int | None = fields.IntField(null=True)
    #: Overrides the category's folder; relative to ``DOWNLOAD_DIR``.
    save_dir: str | None = fields.CharField(max_length=1024, null=True)
    #: The resolved folder, relative to ``DOWNLOAD_DIR``, fixed when it starts.
    #: Kept so a finished download's files survive a change to its category.
    folder: str | None = fields.CharField(max_length=1024, null=True)

    # queue
    queue = fields.ForeignKeyField("model.Queue", related_name="downloads", on_delete=fields.RESTRICT)
    #: Lower runs first, within its queue.
    queue_position: int = fields.BigIntField(default=0)
    #: Not before this; separate from ``next_attempt_at`` so a retry never erases a schedule.
    start_at: datetime | None = fields.DatetimeField(null=True)

    # limits and integrity
    #: Null: the global limit alone. HTTP downloads only; rqbit can't cap one torrent.
    download_limit_bps: int | None = fields.BigIntField(null=True)
    checksum_algo: ChecksumAlgo | None = fields.CharEnumField(ChecksumAlgo, max_length=8, null=True)
    checksum_expected: str | None = fields.CharField(max_length=128, null=True)
    #: Null until checked.
    checksum_ok: bool | None = fields.BooleanField(null=True)

    # progress
    total_bytes: int | None = fields.BigIntField(null=True)
    downloaded_bytes: int = fields.BigIntField(default=0)

    # lifecycle
    error: str | None = fields.TextField(null=True)
    error_code: str | None = fields.CharField(max_length=64, null=True)
    attempts: int = fields.IntField(default=0)
    next_attempt_at: datetime | None = fields.DatetimeField(null=True)
    started_at: datetime | None = fields.DatetimeField(null=True)
    completed_at: datetime | None = fields.DatetimeField(null=True)

    if TYPE_CHECKING:
        category_id: UUID | None
        collection_id: UUID | None
        queue_id: UUID
        #: The one-to-one details; prefetched by every repo read (``RELATED``).
        site_detail: SiteDetail | None
        torrent_detail: TorrentDetail | None
        download_files: fields.ReverseRelation[DownloadFile]
        segments: fields.ReverseRelation[Segment]
        mirrors: fields.ReverseRelation[Mirror]

    def __str__(self) -> str:
        return f"[Download: id {self.id}, platform {self.platform}, status {self.status}]"

    class Meta:
        table: ClassVar[str] = "download"
        table_description: ClassVar[str] = "Download"
        schema: ClassVar[str] = "transfer"
        ordering: ClassVar[list[str]] = ["-created_at"]
        indexes: ClassVar[tuple[Index, ...]] = (
            Index(fields=["status", "created_at"], name="idx_download_status_created"),
            Index(fields=["collection_id", "position"], name="idx_download_collection_position"),
            Index(fields=["queue_id", "status", "queue_position"], name="idx_download_queue_order"),
        )
