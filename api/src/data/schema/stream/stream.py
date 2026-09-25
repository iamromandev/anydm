from __future__ import annotations

import uuid
from typing import Annotated

from pydantic import Field, model_validator

from src.core.base import BaseSchema


class StreamStartRequest(BaseSchema):
    url: Annotated[
        str | None,
        Field(
            default=None,
            min_length=1,
            description=(
                "A page on a site yt-dlp supports, played at up to 1080p, "
                "or a direct http or https URL to a media file"
            ),
        ),
    ]
    torrent: Annotated[
        str | None,
        Field(
            default=None,
            min_length=1,
            description="A magnet link, an http URL to a .torrent, or a base64 .torrent file",
        ),
    ]
    task_id: Annotated[
        uuid.UUID | None,
        Field(default=None, description="A finished download, played from its file on disk"),
    ]
    file_index: Annotated[
        int | None,
        Field(
            default=None,
            ge=0,
            description="With task_id, which of a torrent's files; its largest media file by default",
        ),
    ]

    @model_validator(mode="after")
    def _one_source(self) -> StreamStartRequest:
        sources = [self.url, self.torrent, self.task_id]
        if sum(source is not None for source in sources) != 1:
            raise ValueError("Provide exactly one of url, torrent or task_id")
        if self.file_index is not None and self.task_id is None:
            raise ValueError("file_index goes with task_id")
        return self


class MediaInfoSchema(BaseSchema):
    """What the player needs to choose between a download's file and a session (#94)."""

    #: A torrent's file; ``None`` for a download's one file.
    file_index: int | None = None
    filename: str
    duration_seconds: float
    has_video: bool
    #: For ``canPlayType``: ``None`` when no browser plays it from a file.
    media_type: str | None = None
    #: Where the browser fetches the file itself, with Range.
    file_url: str


class StreamSessionSchema(BaseSchema):
    session_id: str
    playlist_url: str
    status: str
    duration_seconds: float | None = None
    has_video: bool | None = None
