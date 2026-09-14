"""Preset to stream selection. Pure — no network, no pytubefix.

A port of ``resolveYouTubeDownload`` from the Bun API, with two deliberate
differences.

That function resolved every stream URL before choosing, because YouTube
frequently locks adaptive streams. Here the choice is made on metadata alone and
the URL is resolved once, later, by the worker — so a locked stream surfaces as
a retryable download failure rather than as a silent downgrade.

And when no stream is short enough for the requested preset, that function fell
back to the tallest available. This falls back to the *shortest*: a request for
480p that is answered with a 4 GB 4K file has not been answered.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from src.data.type import Kind, Preset
from src.lib.youtube.error import no_format_for_preset
from src.lib.youtube.protocol import StreamInfo

_UNSAFE = re.compile(r"[^\w\s-]", re.UNICODE)
_WHITESPACE = re.compile(r"\s+")
_MAX_STEM = 150


@dataclass(frozen=True, slots=True)
class DownloadPlan:
    kind: Kind
    video_itag: int | None
    audio_itag: int | None
    mime_type: str
    extension: str
    quality: str
    #: Sum of the chosen streams' content lengths, so a two-part download can
    #: report one honest percentage instead of two 0-100 sweeps. ``None`` when
    #: YouTube did not report a size for every chosen stream.
    expected_bytes: int | None = None


def safe_filename(title: str, suffix: str, extension: str) -> str:
    r"""A filesystem-safe name built from ``title``.

    Unlike the Bun version this keeps non-ASCII letters — Python's ``\w`` is
    Unicode-aware — so a Japanese or Cyrillic title survives instead of
    collapsing to an empty stem. The stem is capped so the full path stays
    inside the 255-byte limit every common filesystem enforces.
    """
    stem = _WHITESPACE.sub("_", _UNSAFE.sub("", (title or "").strip())).strip("_")
    stem = stem[:_MAX_STEM] or "download"
    tail = f"_{suffix}" if suffix else ""
    return f"{stem}{tail}.{extension}"


def _extension_of(mime_type: str | None, fallback: str) -> str:
    if not mime_type:
        return fallback
    parts = mime_type.split(";")[0].split("/")
    return parts[1] if len(parts) > 1 and parts[1] else fallback


def _total_of(*streams: StreamInfo) -> int | None:
    """The combined size of the chosen streams, or ``None`` if any is unknown.

    Partial knowledge is worse than none here: reporting a total that omits the
    audio track would make the percentage overshoot and stall at 100.
    """
    sizes = [s.content_length for s in streams]
    if any(not size for size in sizes):
        return None
    return sum(size for size in sizes if size is not None)


def _height(stream: StreamInfo) -> int:
    return stream.height or 0


def _bitrate(stream: StreamInfo) -> int:
    return stream.bitrate or 0


def _pick_by_height(streams: list[StreamInfo], target: int | None) -> StreamInfo | None:
    """The tallest stream not exceeding ``target``, else the shortest there is.

    ``streams`` arrives sorted tallest-first. A ``target`` of ``None`` means
    "best", so the first entry wins outright. When nothing fits, the last entry
    — the shortest — is the closest thing to what was asked for.
    """
    if not streams:
        return None
    if target is None:
        return streams[0]
    return next((s for s in streams if _height(s) <= target), streams[-1])


def select_plan(streams: list[StreamInfo], preset: Preset) -> DownloadPlan:
    combined = sorted((s for s in streams if s.has_video and s.has_audio), key=_height, reverse=True)
    video_only = sorted((s for s in streams if s.has_video and not s.has_audio), key=_height, reverse=True)
    audio_only = sorted((s for s in streams if s.has_audio and not s.has_video), key=_bitrate, reverse=True)

    if preset == Preset.MP3:
        if not audio_only:
            raise no_format_for_preset(preset.value)
        return DownloadPlan(
            kind=Kind.AUDIO,
            video_itag=None,
            audio_itag=audio_only[0].itag,
            mime_type="audio/mpeg",
            extension="mp3",
            quality="mp3",
            expected_bytes=_total_of(audio_only[0]),
        )

    target = preset.target_height
    best_combined = _pick_by_height(combined, target)
    best_video = _pick_by_height(video_only, target)
    best_audio = audio_only[0] if audio_only else None

    # Prefer the combined stream whenever it is at least as tall, since it needs
    # no muxing pass at all.
    if best_combined is not None and (best_video is None or _height(best_combined) >= _height(best_video)):
        return DownloadPlan(
            kind=Kind.VIDEO,
            video_itag=best_combined.itag,
            audio_itag=None,
            mime_type=(best_combined.mime_type or "video/mp4").split(";")[0],
            extension=_extension_of(best_combined.mime_type, "mp4"),
            quality=best_combined.quality or preset.value,
            expected_bytes=_total_of(best_combined),
        )

    if best_video is not None and best_audio is not None:
        return DownloadPlan(
            kind=Kind.VIDEO,
            video_itag=best_video.itag,
            audio_itag=best_audio.itag,
            mime_type="video/mp4",
            extension="mp4",
            quality=best_video.quality or preset.value,
            expected_bytes=_total_of(best_video, best_audio),
        )

    raise no_format_for_preset(preset.value)
