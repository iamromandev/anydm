from __future__ import annotations

from datetime import datetime

from tortoise import fields
from tortoise.indexes import Index

from src.core.base import Base
from src.data.type import Kind, Platform, Preset, TaskStatus


class Task(Base):
    """One download, from the request that created it to the file it produced."""

    # source
    source_url: str = fields.TextField()
    platform: Platform = fields.CharEnumField(Platform, max_length=16, db_index=True)
    video_id: str | None = fields.CharField(max_length=64, null=True, db_index=True)

    # request
    preset: Preset = fields.CharEnumField(Preset, max_length=8)
    kind: Kind = fields.CharEnumField(Kind, max_length=8)

    # resolved plan. The itags rather than a URL: stream URLs expire within
    # hours and bind to the requesting IP, so a resumed download re-resolves.
    title: str = fields.CharField(max_length=512, default="")
    filename: str = fields.CharField(max_length=512, default="")
    mime_type: str | None = fields.CharField(max_length=128, null=True)
    video_itag: int | None = fields.IntField(null=True)
    audio_itag: int | None = fields.IntField(null=True)

    # progress. Always byte-download progress: it reaches 100 when the last
    # byte lands and stays there through muxing, which ``status`` reports.
    status: TaskStatus = fields.CharEnumField(TaskStatus, max_length=16, db_index=True)
    progress: int = fields.IntField(default=0)
    downloaded_bytes: int = fields.BigIntField(default=0)
    total_bytes: int | None = fields.BigIntField(null=True)
    speed_bps: int = fields.BigIntField(default=0)
    eta_seconds: int | None = fields.IntField(null=True)

    # result
    file_path: str | None = fields.CharField(max_length=1024, null=True)
    file_size: int | None = fields.BigIntField(null=True)

    # lifecycle
    error: str | None = fields.TextField(null=True)
    error_code: str | None = fields.CharField(max_length=64, null=True)
    attempts: int = fields.IntField(default=0)
    next_attempt_at: datetime | None = fields.DatetimeField(null=True)
    started_at: datetime | None = fields.DatetimeField(null=True)
    completed_at: datetime | None = fields.DatetimeField(null=True)
    #: Written by the progress flush. Startup recovery does not consult it —
    #: with one process every in-flight row at boot is an orphan. It is here for
    #: observability, and so a second process needs no migration.
    heartbeat_at: datetime | None = fields.DatetimeField(null=True)

    class Meta:
        table = "download_task"
        indexes = (
            Index(fields=("status", "created_at"), name="idx_task_status_created"),
        )
