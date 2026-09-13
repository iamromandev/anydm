import pytest
from src.core.error import Error
from src.data.type import Kind, Preset
from src.lib.youtube.format import safe_filename, select_plan
from src.lib.youtube.protocol import StreamInfo


def _video(itag: int, height: int, *, audio: bool = False) -> StreamInfo:
    return StreamInfo(
        itag=itag,
        mime_type="video/mp4",
        quality=f"{height}p",
        height=height,
        has_video=True,
        has_audio=audio,
    )


def _audio(itag: int, bitrate: int) -> StreamInfo:
    return StreamInfo(itag=itag, mime_type="audio/mp4", bitrate=bitrate, has_audio=True)


def test_mp3_picks_the_highest_bitrate_audio() -> None:
    plan = select_plan([_audio(139, 48000), _audio(140, 128000), _video(137, 1080)], Preset.MP3)
    assert plan.kind == Kind.AUDIO
    assert plan.audio_itag == 140
    assert plan.video_itag is None
    assert plan.extension == "mp3"
    assert plan.mime_type == "audio/mpeg"


def test_mp3_without_audio_is_rejected() -> None:
    with pytest.raises(Error):
        select_plan([_video(137, 1080)], Preset.MP3)


def test_combined_stream_wins_when_it_is_at_least_as_tall() -> None:
    plan = select_plan([_video(18, 720, audio=True), _video(136, 720), _audio(140, 128000)], Preset.P720)
    assert plan.kind == Kind.VIDEO
    assert plan.video_itag == 18
    assert plan.audio_itag is None


def test_video_plus_audio_when_the_combined_stream_is_shorter() -> None:
    plan = select_plan([_video(18, 360, audio=True), _video(137, 1080), _audio(140, 128000)], Preset.P1080)
    assert plan.kind == Kind.VIDEO
    assert plan.video_itag == 137
    assert plan.audio_itag == 140


def test_best_takes_the_tallest_available() -> None:
    plan = select_plan([_video(137, 1080), _video(313, 2160), _audio(140, 128000)], Preset.BEST)
    assert plan.video_itag == 313


def test_height_target_never_exceeds_the_request() -> None:
    plan = select_plan([_video(313, 2160), _video(137, 1080), _video(135, 480), _audio(140, 128000)], Preset.P1080)
    assert plan.video_itag == 137


def test_falls_back_to_the_smallest_when_everything_exceeds_the_target() -> None:
    # Deliberate deviation from the Bun original, which returned the *tallest*
    # here — asking for 480p and being handed a 4K file is a bug, not a feature.
    plan = select_plan([_video(313, 2160), _video(137, 1080), _audio(140, 128000)], Preset.P480)
    assert plan.video_itag == 137


def test_no_video_at_all_is_rejected() -> None:
    with pytest.raises(Error) as caught:
        select_plan([_audio(140, 128000)], Preset.P1080)
    assert caught.value.code == 422


def test_video_only_without_audio_is_rejected() -> None:
    with pytest.raises(Error):
        select_plan([_video(137, 1080)], Preset.P1080)


@pytest.mark.parametrize(
    ("title", "suffix", "extension", "expected"),
    [
        ("Never Gonna Give You Up", "1080p", "mp4", "Never_Gonna_Give_You_Up_1080p.mp4"),
        ("Rick / Astley: Live!", "720p", "mp4", "Rick_Astley_Live_720p.mp4"),
        ("  spaced  out  ", "", "mp3", "spaced_out.mp3"),
        ("", "", "mp4", "download.mp4"),
        ("...", "", "mp4", "download.mp4"),
    ],
)
def test_safe_filename(title: str, suffix: str, extension: str, expected: str) -> None:
    assert safe_filename(title, suffix, extension) == expected


def test_safe_filename_keeps_non_ascii_letters() -> None:
    # Python's \w is Unicode-aware, unlike the Bun version's, so a title with no
    # ASCII letters keeps its name instead of collapsing to "download".
    assert safe_filename("日本語の動画", "720p", "mp4") == "日本語の動画_720p.mp4"


def test_safe_filename_truncates_very_long_titles() -> None:
    name = safe_filename("x" * 500, "1080p", "mp4")
    assert len(name) <= 200
    assert name.endswith("_1080p.mp4")
