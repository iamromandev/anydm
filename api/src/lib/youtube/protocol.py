"""The boundary between this project and whatever library talks to YouTube.

Nothing outside ``client.py`` imports yt-dlp. Everything else codes against
these dataclasses and this Protocol, which is also what lets the tests run
without a network.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol


@dataclass(frozen=True, slots=True)
class Thumbnail:
    url: str
    width: int
    height: int


@dataclass(frozen=True, slots=True)
class StreamInfo:
    itag: int
    mime_type: str | None = None
    quality: str | None = None
    height: int | None = None
    bitrate: int | None = None
    has_video: bool = False
    has_audio: bool = False
    content_length: int | None = None


@dataclass(frozen=True, slots=True)
class VideoInfo:
    video_id: str
    title: str
    author: str = ""
    channel_id: str = ""
    description: str = ""
    length_seconds: int = 0
    view_count: int = 0
    upload_date: str = ""
    is_live: bool = False
    thumbnails: list[Thumbnail] = field(default_factory=list)
    streams: list[StreamInfo] = field(default_factory=list)


class YouTubeClient(Protocol):
    async def fetch_info(self, video_id: str) -> VideoInfo:
        """Metadata and the full stream list for ``video_id``."""
        ...

    async def stream_url(self, video_id: str, itag: int) -> str:
        """A fresh, playable URL for one stream.

        Always re-resolved rather than cached: these expire within hours and
        bind to the requesting IP.
        """
        ...
