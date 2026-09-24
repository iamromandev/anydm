from __future__ import annotations

from typing import Annotated

from pydantic import Field

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


class StreamSessionSchema(BaseSchema):
    session_id: str
    playlist_url: str
    status: str
    duration_seconds: float | None = None
    has_video: bool | None = None
