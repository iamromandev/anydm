"""YouTube's streams, as the plans and file names the task table stores. Pure.

The selection rule itself lives in ``src/lib/site/format.py``, shared with every
other site; this adapts YouTube's itag-shaped streams to it and back.

The choice is made on metadata alone and the URL is resolved later, by the
worker, so a locked stream surfaces as a retryable download failure rather than
as a silent downgrade.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from src.data.type import Kind, Preset
from src.lib.site.format import Format
from src.lib.site.format import select_plan as select_site_plan
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


def _to_format(stream: StreamInfo) -> Format:
    """A YouTube stream as a site-neutral format.

    ``StreamInfo`` says whether a track is present rather than naming its
    codec, so the codecs here are placeholders that only carry that fact.
    """
    subtype = (stream.mime_type or "").split(";")[0].partition("/")[2]
    return Format(
        id=str(stream.itag),
        protocol="https",
        ext=subtype,
        vcodec="video" if stream.has_video else "none",
        acodec="audio" if stream.has_audio else "none",
        height=stream.height,
        bitrate=stream.bitrate,
        size=stream.content_length,
    )


def _itag(part: Format | None) -> int | None:
    return int(part.id) if part is not None else None


def select_plan(streams: list[StreamInfo], preset: Preset) -> DownloadPlan:
    """YouTube's plan, chosen by the same rule as every other site's.

    Only HTTPS streams ever reach here (the client drops the rest), so the
    fragment path never comes into it. Sizes are YouTube's exact ones.
    """
    plan = select_site_plan([_to_format(stream) for stream in streams], preset)
    return DownloadPlan(
        kind=plan.kind,
        video_itag=_itag(plan.video),
        audio_itag=_itag(plan.audio),
        mime_type=plan.mime_type,
        extension=plan.extension,
        quality=plan.quality,
        expected_bytes=plan.expected_bytes,
    )
