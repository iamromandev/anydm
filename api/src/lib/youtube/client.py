"""pytubefix adapter. The only module in this project that imports pytubefix.

Two things earn the wrapper. pytubefix is synchronous and does blocking HTTP, so
every call goes through ``asyncio.to_thread``. And its failures arrive as loosely
typed exceptions with human-readable messages, which ``_classify`` turns into
this project's ``Error`` — including the ``retry_able`` flag the worker reads.
"""

from __future__ import annotations

import asyncio
import re
from functools import lru_cache
from typing import Any

from loguru import logger
from pytubefix import YouTube

from src.core.error import Error
from src.lib.youtube import error as yt_error
from src.lib.youtube.protocol import StreamInfo, Thumbnail, VideoInfo, YouTubeClient

_WATCH_URL = "https://www.youtube.com/watch?v={video_id}"
_HEIGHT = re.compile(r"(\d{3,4})p")

_MISSING_MARKERS = ("private", "unavailable", "removed", "deleted", "does not exist")
_FORBIDDEN_MARKERS = ("age restricted", "age-restricted", "members-only", "members only", "login required")


def _height_of(raw: Any) -> int | None:
    """The stream's pixel height.

    ``_height`` comes straight from YouTube's stream dict and is exact, but it
    is absent for some formats. The itag profile's ``resolution`` label
    ("1080p") is the fallback.
    """
    height = getattr(raw, "_height", None)
    if height:
        return int(height)
    match = _HEIGHT.search(getattr(raw, "resolution", None) or "")
    return int(match.group(1)) if match else None


def _to_stream_info(raw: Any) -> StreamInfo:
    """One pytubefix ``Stream`` as a plain ``StreamInfo``.

    Every attribute is read with ``getattr`` and a default: pytubefix omits
    fields per stream type, and a missing one should narrow the choices rather
    than raise.

    ``_filesize`` rather than the public ``filesize``: the property issues a
    HEAD request when the cached value is 0, which would mean one network round
    trip per stream on every extract. The cached value is YouTube's own
    ``contentLength``, and 0 means it did not say.
    """
    return StreamInfo(
        itag=int(getattr(raw, "itag", 0)),
        mime_type=getattr(raw, "mime_type", None),
        quality=getattr(raw, "resolution", None),
        height=_height_of(raw),
        bitrate=getattr(raw, "bitrate", None),
        has_video=bool(getattr(raw, "includes_video_track", False)),
        has_audio=bool(getattr(raw, "includes_audio_track", False)),
        content_length=getattr(raw, "_filesize", None) or None,
    )


def _classify(exc: Exception) -> Error:
    """A pytubefix exception as an ``Error``, with the retry decision attached.

    Matched on the message rather than the exception class on purpose:
    pytubefix raises several near-identical types and renames them between
    releases, while the wording of these three cases has been stable.
    """
    text = str(exc).lower()
    if any(marker in text for marker in _MISSING_MARKERS):
        return yt_error.video_unavailable(str(exc))
    if any(marker in text for marker in _FORBIDDEN_MARKERS):
        return yt_error.video_forbidden(str(exc))
    return yt_error.extraction_failed(str(exc))


class PytubefixClient(YouTubeClient):
    async def fetch_info(self, video_id: str) -> VideoInfo:
        return await asyncio.to_thread(self._fetch_info_blocking, video_id)

    async def stream_url(self, video_id: str, itag: int) -> str:
        return await asyncio.to_thread(self._stream_url_blocking, video_id, itag)

    def _youtube(self, video_id: str) -> YouTube:
        return YouTube(_WATCH_URL.format(video_id=video_id))

    def _fetch_info_blocking(self, video_id: str) -> VideoInfo:
        try:
            yt = self._youtube(video_id)
            thumbnails = [Thumbnail(url=yt.thumbnail_url, width=0, height=0)] if yt.thumbnail_url else []
            return VideoInfo(
                video_id=video_id,
                title=yt.title or "",
                author=yt.author or "",
                channel_id=yt.channel_id or "",
                description=yt.description or "",
                length_seconds=int(yt.length or 0),
                view_count=int(yt.views or 0),
                upload_date=yt.publish_date.date().isoformat() if yt.publish_date else "",
                is_live=bool(getattr(yt, "live_streaming", False)),
                thumbnails=thumbnails,
                streams=[_to_stream_info(stream) for stream in yt.streams],
            )
        except Exception as exc:
            logger.error("PytubefixClient|fetch_info({}): {}", video_id, exc)
            raise _classify(exc) from exc

    def _stream_url_blocking(self, video_id: str, itag: int) -> str:
        try:
            stream = self._youtube(video_id).streams.get_by_itag(itag)
        except Exception as exc:
            logger.error("PytubefixClient|stream_url({}, {}): {}", video_id, itag, exc)
            raise _classify(exc) from exc
        if stream is None or not stream.url:
            raise yt_error.no_format_for_preset(str(itag))
        return str(stream.url)


@lru_cache
def get_youtube_client() -> YouTubeClient:
    return PytubefixClient()
