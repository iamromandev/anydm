from __future__ import annotations

import uuid
from datetime import datetime
from typing import Annotated

from pydantic import Field

from src.config import get_settings
from src.core.base import BaseSchema
from src.data.schema.download.torrent import FileSchema
from src.data.type import BulkAction, Kind, Platform, Preset, TaskStatus


class YoutubeDownloadRequest(BaseSchema):
    url: Annotated[str, Field(min_length=1, description="A YouTube video URL")]
    preset: Annotated[Preset, Field(default=Preset.BEST, description="Quality preset")]


class UrlDownloadRequest(BaseSchema):
    url: Annotated[str, Field(min_length=1, description="A direct http or https URL")]


class BulkActionRequest(BaseSchema):
    action: Annotated[BulkAction, Field(description="Which sweep to run")]
    delete_files: Annotated[
        bool,
        Field(
            default=False,
            description=(
                "For clear_finished: take the files of finished downloads too. "
                "A failure's partial file goes either way — there is nothing "
                "in it worth keeping."
            ),
        ),
    ]


class BulkResultSchema(BaseSchema):
    affected: int = 0


class TaskSummarySchema(BaseSchema):
    """How many tasks each sidebar filter would show.

    Counted in the database rather than from the rows the browser happens to
    hold, so the numbers stay right no matter how little of the list has been
    loaded.
    """

    all: int = 0
    downloading: int = 0
    seeding: int = 0
    completed: int = 0


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
    # torrent. Absent, zero and None for every other platform.
    info_hash: str | None = None
    uploaded_bytes: int = 0
    upload_speed_bps: int = 0
    peers_connected: int = 0
    #: ``None`` rather than ``[]`` on purpose: a missing key means "this is not
    #: a torrent", which is the same rule ``segments`` already follows.
    files: list[FileSchema] | None = None
    file_size: int | None = None
    error: str | None = None
    error_code: str | None = None
    attempts: int = 0
    #: When the queue will consider this task again. Set only while a retry is
    #: pending, which is the one case where ``pending`` does not mean "waiting
    #: for a free worker" and the browser has no other way to tell.
    next_attempt_at: datetime | None = None
    #: The retry budget this task is spending, so the client can say "attempt 2
    #: of 3" rather than a number with nothing to measure it against. It comes
    #: from settings rather than the row: it is configuration, identical for
    #: every task, and a column would only let the two disagree.
    max_attempts: int = Field(
        default_factory=lambda: get_settings().download_max_attempts
    )
    created_at: datetime | None = None
    started_at: datetime | None = None
    completed_at: datetime | None = None
