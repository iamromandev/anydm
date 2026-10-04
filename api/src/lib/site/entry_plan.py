"""Choosing a video's formats when it starts rather than when it was added (v0.5).

A playlist's videos are added unplanned: planning takes an extraction, and 5,000
of them cannot happen inside one request. A height preset is a ceiling:
``select_plan`` already takes the tallest at or under it, and a video offering
only taller heights takes its smallest.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from src.core.error import Error
from src.data.type import MediaKind, Preset
from src.lib.site.client import SiteInfo
from src.lib.site.filename import safe_filename
from src.lib.site.format import Format, Plan, select_plan


@dataclass(frozen=True, slots=True)
class Planned:
    """What planning decides, for the worker to write onto the download, its site detail and its file."""

    media_kind: MediaKind
    title: str
    filename: str
    mime_type: str
    video_format: str | None
    audio_format: str | None
    total_bytes: int | None


def is_unplanned(site: Any) -> bool:
    """No format chosen yet: a collection's video, planned when it starts."""
    return not site.video_format and not site.audio_format


def plan_for(formats: list[Format], preset: Preset) -> Plan:
    try:
        return select_plan(formats, preset)
    except Error:
        if preset.target_height is None:
            raise
        heights = sorted({f.height for f in formats if f.has_video and f.height})
        if not heights:
            raise
        smallest = [f for f in formats if not f.has_video or f.height == heights[0]]
        return select_plan(smallest, Preset.BEST)


def leading_number(filename: str) -> str:
    """The ``007_`` a numbered video's name starts with, or ``""``.

    The number is assigned when the video is added (its file row holds just
    the prefix until planning), so planning trusts the name it finds: a
    playlist's video keeps its number across re-plans, and a channel's
    unnumbered video keeps its title.
    """
    found = re.match(r"\d+_", filename)
    return found.group(0) if found else ""


def plan_fields(info: SiteInfo, plan: Plan, *, preset: Preset, title: str, number: str) -> Planned:
    """``plan`` as the fields it sets, keeping the video's number at the front of its name."""
    suffix = "" if preset == Preset.MP3 else plan.quality
    final_title = info.title or title
    return Planned(
        media_kind=plan.kind,
        title=final_title,
        filename=f"{number}{safe_filename(final_title, suffix, plan.extension)}",
        mime_type=plan.mime_type,
        video_format=plan.video.id if plan.video else None,
        audio_format=plan.audio.id if plan.audio else None,
        # Only an exact size: a bar measured against an estimate stalls short of 100.
        total_bytes=None if plan.size_is_estimate else plan.expected_bytes,
    )
