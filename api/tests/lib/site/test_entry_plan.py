"""Choosing a group video's formats when it starts (v0.5)."""

from types import SimpleNamespace

import pytest
from src.core.error import Error
from src.data.type import MediaKind, Preset
from src.lib.site.entry_plan import is_unplanned, leading_number, plan_fields, plan_for
from src.lib.site.format import select_plan

from tests.sites import site_info


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
    assert leading_number("07_Title_720p.mp4") == "07_"
    assert leading_number("007_") == "007_"
    assert leading_number("Title_720p.mp4") == ""
    assert leading_number("") == ""


def test_an_unnumbered_title_keeps_no_prefix() -> None:
    # A channel's tab isn't numbered; its file row holds "" until planning.
    assert leading_number("") == ""


def test_plan_fields_keeps_the_number_and_names_the_file() -> None:
    info = site_info("vimeo")
    plan = select_plan(info.formats, Preset.P720)

    planned = plan_fields(info, plan, preset=Preset.P720, title="", number="07_")

    assert planned.filename.startswith("07_") and planned.filename.endswith(f"_{plan.quality}.{plan.extension}")
    assert planned.title == info.title
    assert planned.media_kind == MediaKind.VIDEO
    assert planned.video_format == (plan.video.id if plan.video else None)


def test_an_estimate_is_not_a_size_and_mp3_has_no_quality_suffix() -> None:
    plan = SimpleNamespace(
        kind=MediaKind.AUDIO, quality="160k", extension="mp3", mime_type="audio/mpeg",
        video=None, audio=SimpleNamespace(id="140"), size_is_estimate=True, expected_bytes=900,
    )
    planned = plan_fields(SimpleNamespace(title=""), plan, preset=Preset.MP3, title="Song", number="")  # ty: ignore[invalid-argument-type]
    assert planned.total_bytes is None and planned.video_format is None
    assert planned.title == "Song" and planned.filename == "Song.mp3"


def test_unplanned_means_no_format_chosen_yet() -> None:
    assert is_unplanned(SimpleNamespace(video_format=None, audio_format=None))
    assert not is_unplanned(SimpleNamespace(video_format="137", audio_format=None))
