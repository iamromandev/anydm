from __future__ import annotations

import uuid
from datetime import datetime
from typing import Annotated

from pydantic import Field

from src.core.base import BaseSchema
from src.data.type import Kind, Platform, Preset, TaskStatus


class YoutubeDownloadRequest(BaseSchema):
    url: Annotated[str, Field(min_length=1, description="A YouTube video URL")]
    preset: Annotated[Preset, Field(default=Preset.BEST, description="Quality preset")]


class UrlDownloadRequest(BaseSchema):
    url: Annotated[str, Field(min_length=1, description="A direct http or https URL")]


class TaskSchema(BaseSchema):
    id: uuid.UUID
    source_url: str
    platform: Platform
    video_id: str | None = None
    preset: Preset
    kind: Kind
    title: str = ""
    filename: str = ""
    mime_type: str | None = None
    status: TaskStatus
    progress: int = 0
    downloaded_bytes: int = 0
    total_bytes: int | None = None
    speed_bps: int = 0
    eta_seconds: int | None = None
    file_size: int | None = None
    error: str | None = None
    error_code: str | None = None
    attempts: int = 0
    created_at: datetime | None = None
    started_at: datetime | None = None
    completed_at: datetime | None = None
