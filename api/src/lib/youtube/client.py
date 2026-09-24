"""yt-dlp adapter. The only module in this project that imports yt-dlp.

Three things earn the wrapper. yt-dlp is synchronous and does blocking HTTP, so
every call goes through ``asyncio.to_thread``, under a ceiling. Its failures
arrive as one loosely typed ``DownloadError`` with a human-readable message,
which ``_classify`` turns into this project's ``Error``, including the
``retry_able`` flag the worker reads. And it reports every format YouTube
offers, most of which this project cannot use: only plain HTTPS formats with a
numeric itag are streams here, because those are what the segmented engine
fetches and what the task table stores.

YouTube's JavaScript challenges are solved by Deno when it is on PATH (the
image ships it). Without it yt-dlp falls back to a client that needs no
JavaScript, which it has deprecated and which offers fewer formats.
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from functools import lru_cache
from typing import Any

from loguru import logger

from src.core.error import Error
from src.lib.youtube import error as yt_error
from src.lib.youtube.protocol import StreamInfo, Thumbnail, VideoInfo, YouTubeClient

_WATCH_URL = "https://www.youtube.com/watch?v={video_id}"

#: A blocking ``url -> info dict`` call; yt-dlp's in production, a fake in tests.
Extract = Callable[[str], dict[str, Any]]

_MISSING_MARKERS = ("private", "unavailable", "removed", "deleted", "does not exist")
_FORBIDDEN_MARKERS = (
    "age restricted",
    "age-restricted",
    "confirm your age",
    "members-only",
    "members only",
    "login required",
    "in your country",
)

#: yt-dlp names the container, not the MIME subtype, for M4A audio.
_SUBTYPE = {"m4a": "mp4"}


def _present(codec: Any) -> bool:
    """yt-dlp writes the string ``"none"`` for an absent track; ``None`` is unknown."""
    return codec is not None and codec != "none"


def _to_stream_info(raw: dict[str, Any]) -> StreamInfo | None:
    """One yt-dlp format as a ``StreamInfo``, or ``None`` when it is not a stream here.

    Only ``https`` formats count: YouTube's HLS formats have numeric ids too,
    but they are playlists of fragments, and storyboards are images.
    ``filesize`` rather than ``filesize_approx``: a plan's total must not
    overshoot, and an estimate is only an estimate.
    """
    format_id = str(raw.get("format_id") or "")
    if raw.get("protocol") != "https" or not format_id.isdigit():
        return None

    has_video = _present(raw.get("vcodec"))
    has_audio = _present(raw.get("acodec"))
    if not has_video and not has_audio:
        return None

    ext = str(raw.get("ext") or "")
    height = raw.get("height") if has_video else None
    bitrate = raw.get("tbr") or raw.get("abr")
    return StreamInfo(
        itag=int(format_id),
        mime_type=f"{'video' if has_video else 'audio'}/{_SUBTYPE.get(ext, ext)}" if ext else None,
        quality=f"{height}p" if height else None,
        height=int(height) if height else None,
        bitrate=round(bitrate * 1000) if bitrate else None,
        has_video=has_video,
        has_audio=has_audio,
        content_length=raw.get("filesize") or None,
    )


def _upload_date(raw: Any) -> str:
    """``20091025`` as ``2009-10-25``; anything else as nothing."""
    text = str(raw or "")
    return f"{text[:4]}-{text[4:6]}-{text[6:]}" if len(text) == 8 and text.isdigit() else ""


def _to_video_info(video_id: str, info: dict[str, Any]) -> VideoInfo:
    thumbnail = info.get("thumbnail")
    return VideoInfo(
        video_id=str(info.get("id") or video_id),
        title=info.get("title") or "",
        author=info.get("uploader") or info.get("channel") or "",
        channel_id=info.get("channel_id") or "",
        description=info.get("description") or "",
        length_seconds=int(info.get("duration") or 0),
        view_count=int(info.get("view_count") or 0),
        upload_date=_upload_date(info.get("upload_date")),
        is_live=bool(info.get("is_live")),
        thumbnails=[Thumbnail(url=thumbnail, width=0, height=0)] if thumbnail else [],
        streams=[s for s in (_to_stream_info(f) for f in info.get("formats") or []) if s is not None],
    )


def _classify(exc: Exception) -> Error:
    """A yt-dlp failure as an ``Error``, with the retry decision attached.

    yt-dlp raises one ``DownloadError`` for everything, so the message is all
    there is to go on. YouTube's bot check ("Sign in to confirm you're not a
    bot") deliberately matches nothing here: it clears with time, so it lands
    in the retryable default.
    """
    text = str(exc).lower()
    if any(marker in text for marker in _MISSING_MARKERS):
        return yt_error.video_unavailable(str(exc))
    if any(marker in text for marker in _FORBIDDEN_MARKERS):
        return yt_error.video_forbidden(str(exc))
    return yt_error.extraction_failed(str(exc))


class _Log:
    """Routes yt-dlp's own messages into loguru; its chatter stays out."""

    def debug(self, message: str) -> None:
        return None

    def info(self, message: str) -> None:
        return None

    def warning(self, message: str) -> None:
        logger.warning("yt-dlp|{}", message)

    def error(self, message: str) -> None:
        logger.error("yt-dlp|{}", message)


def _ytdlp_extract(url: str) -> dict[str, Any]:
    # Imported here so the module loads, and its tests run, without touching
    # yt-dlp's extractor registry until a real extraction is asked for.
    import yt_dlp

    options = {
        "quiet": True,
        "skip_download": True,
        "noplaylist": True,
        "socket_timeout": 20,
        "logger": _Log(),
    }
    with yt_dlp.YoutubeDL(options) as ydl:
        return ydl.extract_info(url, download=False)


class YtDlpClient(YouTubeClient):
    def __init__(self, extract: Extract = _ytdlp_extract, *, timeout_s: float = 60.0) -> None:
        self._extract = extract
        self._timeout_s = timeout_s

    async def fetch_info(self, video_id: str) -> VideoInfo:
        return _to_video_info(video_id, await self._info(video_id))

    async def stream_url(self, video_id: str, itag: int) -> str:
        """A fresh URL for one itag, from a fresh extraction every time."""
        info = await self._info(video_id)
        wanted = str(itag)
        for raw in info.get("formats") or []:
            if str(raw.get("format_id")) == wanted and raw.get("protocol") == "https" and raw.get("url"):
                return str(raw["url"])
        raise yt_error.no_format_for_preset(wanted)

    async def _info(self, video_id: str) -> dict[str, Any]:
        url = _WATCH_URL.format(video_id=video_id)
        try:
            # The ceiling abandons the wait, not the thread: yt-dlp cannot be
            # interrupted, and its own socket timeout bounds what it leaves behind.
            return await asyncio.wait_for(asyncio.to_thread(self._extract, url), timeout=self._timeout_s)
        except TimeoutError as exc:
            logger.error("YtDlpClient|{} took longer than {}s", video_id, self._timeout_s)
            raise yt_error.extraction_failed(f"timed out after {self._timeout_s:g}s") from exc
        except Error:
            raise
        except Exception as exc:
            logger.error("YtDlpClient|{}: {}", video_id, exc)
            raise _classify(exc) from exc


@lru_cache
def get_youtube_client() -> YouTubeClient:
    return YtDlpClient()
