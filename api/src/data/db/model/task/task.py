from __future__ import annotations

from datetime import datetime
from typing import ClassVar

from tortoise import fields
from tortoise.indexes import Index

from src.core.base import Base
from src.data.type import Kind, Platform, Preset, TaskStatus


class Task(Base):
    """One download, from the request that created it to the file it produced."""

    # group (v0.5)
    parent = fields.ForeignKeyField(
        "model.Task", related_name="playlist_entries", null=True, on_delete=fields.CASCADE
    )

    position: int | None = fields.IntField(null=True)

    # source
    source_url: str = fields.TextField()
    platform: Platform = fields.CharEnumField(Platform, max_length=16, db_index=True)
    extractor: str | None = fields.CharField(max_length=64, null=True)
    video_id: str | None = fields.CharField(max_length=64, null=True, db_index=True)
    info_hash: str | None = fields.CharField(max_length=40, null=True, db_index=True)

    # request
    preset: Preset = fields.CharEnumField(Preset, max_length=8)
    kind: Kind = fields.CharEnumField(Kind, max_length=8)

    # resolved plan. The site's format ids rather than a URL: stream URLs expire
    # within hours and bind to the requesting IP, so a resumed download
    # re-resolves. A YouTube format id is its itag, as a string.
    title: str = fields.CharField(max_length=512, default="")
    filename: str = fields.CharField(max_length=512, default="")
    mime_type: str | None = fields.CharField(max_length=128, null=True)
    video_format: str | None = fields.CharField(max_length=64, null=True)
    audio_format: str | None = fields.CharField(max_length=64, null=True)

    # progress. Always byte-download progress: it reaches 100 when the last
    # byte lands and stays there through muxing, which ``status`` reports.
    status: TaskStatus = fields.CharEnumField(TaskStatus, max_length=16, db_index=True)
    progress: int = fields.IntField(default=0)
    downloaded_bytes: int = fields.BigIntField(default=0)
    total_bytes: int | None = fields.BigIntField(null=True)
    speed_bps: int = fields.BigIntField(default=0)
    eta_seconds: int | None = fields.IntField(null=True)
    uploaded_bytes: int = fields.BigIntField(default=0)
    upload_speed_bps: int = fields.BigIntField(default=0)
    peers_connected: int = fields.IntField(default=0)

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
    heartbeat_at: datetime | None = fields.DatetimeField(null=True)

    def __str__(self) -> str:
        return (
            f"[Task: id {self.id}, platform {self.platform}, kind {self.kind}, "
            f"preset {self.preset}, status {self.status}, progress {self.progress}]"
        )

    class Meta:
        table: ClassVar[str] = "task"
        table_description: ClassVar[str] = "Task"
        ordering: ClassVar[list[str]] = ["-created_at"]
        indexes: ClassVar[tuple[Index, ...]] = (
            Index(fields=["status", "created_at"], name="idx_task_status_created"),
            Index(fields=["parent_id"], name="idx_task_parent"),
            Index(fields=["parent_id", "position"], name="idx_task_parent_position"),
        )
