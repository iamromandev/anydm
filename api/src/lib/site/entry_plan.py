"""Choosing a video's formats when it starts rather than when it was added (v0.5).

A playlist's videos are added unplanned: planning takes an extraction, and 5,000
of them cannot happen inside one request. A height preset is a ceiling:
``select_plan`` already takes the tallest at or under it, and a video offering
only taller heights takes its smallest.
"""

from __future__ import annotations

import re
from typing import Any

from src.core.error import Error
from src.data.type import Preset
from src.lib.site.client import SiteInfo
from src.lib.site.filename import safe_filename
from src.lib.site.format import Format, Plan, select_plan


def is_unplanned(task: Any) -> bool:
    return not task.video_format and not task.audio_format


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


def number_of(filename: str, position: int | None) -> str:
    """The ``007_`` a numbered video's name starts with, or ``""``.

    Matched against the video's own position, so a channel's video titled
    "2001_…" (whose files aren't numbered) keeps its title.
    """
    if position is None:
        return ""
    found = re.match(rf"0*{position}_", filename)
    return found.group(0) if found else ""


def apply_plan(task: Any, info: SiteInfo, plan: Plan) -> list[str]:
    """Write ``plan`` onto ``task``, keeping its number. Returns the fields to save."""
    suffix = "" if task.preset == Preset.MP3 else plan.quality
    task.kind = plan.kind
    task.title = info.title or task.title
    task.filename = f"{task.filename}{safe_filename(task.title, suffix, plan.extension)}"
    task.mime_type = plan.mime_type
    task.video_format = plan.video.id if plan.video else None
    task.audio_format = plan.audio.id if plan.audio else None
    task.total_bytes = None if plan.size_is_estimate else plan.expected_bytes
    return ["kind", "title", "filename", "mime_type", "video_format", "audio_format", "total_bytes"]
