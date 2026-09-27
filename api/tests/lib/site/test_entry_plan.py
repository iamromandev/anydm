"""Choosing a group video's formats when it starts (v0.5)."""

from typing import Any

import pytest
from src.core.error import Error
from src.data.type import Kind, Preset
from src.lib.site.entry_plan import apply_plan, is_unplanned, number_of, plan_for
from src.lib.site.format import select_plan

from tests.sites import site_info


def _task(**fields: Any) -> Any:
    base = {
        "filename": "",
        "video_format": None,
        "audio_format": None,
        "kind": Kind.VIDEO,
        "title": "",
        "mime_type": None,
        "total_bytes": None,
        "preset": Preset.P720,
    }
    return type("T", (), {**base, **fields})()


def test_a_preset_taller_than_the_video_takes_the_tallest() -> None:
    assert plan_for(site_info("vimeo").formats, Preset.P2160).quality == "1080p"


def test_a_video_offering_only_taller_heights_takes_its_smallest() -> None:
    formats = [f for f in site_info("youtube").formats if not f.has_video or (f.height or 0) >= 720]
    smallest = min(f.height for f in formats if f.has_video and f.height)

    plan = plan_for(formats, Preset.P480)

    assert plan.video is not None and plan.video.height == smallest


def test_mp3_with_no_audio_still_fails() -> None:
    video_only = [f for f in site_info("vimeo").formats if f.has_video and not f.has_audio]

    with pytest.raises(Error):
        plan_for(video_only, Preset.MP3)


def test_the_number_is_what_survives_a_re_plan() -> None:
    assert number_of("07_Title_720p.mp4", 7) == "07_"
    assert number_of("007_", 7) == "007_"
    assert number_of("Title_720p.mp4", 7) == ""
    assert number_of("anything", None) == ""


def test_a_title_starting_with_digits_is_not_a_number() -> None:
    # A channel's tab isn't numbered; "2001_" is the title, at position 5.
    assert number_of("2001_A_Space_Odyssey_720p.mp4", 5) == ""


def test_apply_plan_keeps_the_number_and_names_the_file() -> None:
    info = site_info("vimeo")
    task = _task(filename="07_")
    assert is_unplanned(task)

    plan = select_plan(info.formats, Preset.P720)
    fields = apply_plan(task, info, plan)

    assert task.filename.startswith("07_") and task.filename.endswith(f"_{plan.quality}.{plan.extension}")
    assert task.title == info.title
    assert not is_unplanned(task)
    assert {"filename", "video_format", "title", "kind"} <= set(fields)
