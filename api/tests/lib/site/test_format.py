"""Format selection for any site, over what yt-dlp really reported (#53's fixtures)."""

import json
from pathlib import Path

import pytest
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.data.type import Kind, Preset
from src.lib.site.format import Format, fetchable_plan, playback_plan, select_plan, usable_presets

FIXTURES = Path(__file__).parents[2] / "fixtures" / "ytdlp"


def _formats(site: str) -> list[Format]:
    raw = json.loads((FIXTURES / f"{site}.json").read_text())["formats"]
    return [Format.from_ytdlp(f) for f in raw]


def _one(site: str, format_id: str) -> Format:
    return next(f for f in _formats(site) if f.id == format_id)


def _picked(site: str, preset: Preset, *, allow_fragmented: bool = True) -> tuple[str | None, str | None]:
    plan = select_plan(_formats(site), preset, allow_fragmented=allow_fragmented)
    return (plan.video.id if plan.video else None, plan.audio.id if plan.audio else None)


# --- reading a format ---------------------------------------------------------


def test_storyboards_are_not_media() -> None:
    assert not _one("youtube", "sb0").media
    assert not _one("twitch", "sb1").media


def test_an_unset_codec_with_a_height_is_a_combined_video() -> None:
    # Vimeo's and X's progressive MP4s: yt-dlp does not know their codecs.
    vimeo = _one("vimeo", "http-1080p")

    assert (vimeo.has_video, vimeo.has_audio) == (True, True)


def test_the_string_none_means_the_track_is_absent() -> None:
    video = _one("youtube", "137")
    audio = _one("youtube", "140")

    assert (video.has_video, video.has_audio) == (True, False)
    assert (audio.has_video, audio.has_audio) == (False, True)


def test_no_video_and_an_unknown_audio_codec_is_audio() -> None:
    # YouTube's and Vimeo's HLS audio: vcodec "none", acodec unset.
    hls_audio = _one("youtube", "233")

    assert (hls_audio.has_video, hls_audio.has_audio) == (False, True)


def test_plain_http_is_not_fragmented_and_hls_is() -> None:
    assert not _one("soundcloud", "http_mp3_0_0").fragmented
    assert not _one("youtube", "137").fragmented
    assert _one("soundcloud", "hls_mp3_0_0").fragmented


def test_bitrate_is_in_bits_per_second() -> None:
    assert _one("youtube", "137").bitrate == 3_038_377


# --- choosing -----------------------------------------------------------------


@pytest.mark.parametrize(
    ("site", "preset", "expected"),
    [
        # YouTube: H.264 at each height, the M4A track; the same picks as before.
        ("youtube", Preset.BEST, ("313", "140")),
        ("youtube", Preset.P1080, ("137", "140")),
        ("youtube", Preset.P720, ("136", "140")),
        ("youtube", Preset.P480, ("135", "140")),
        ("youtube", Preset.MP3, (None, "140")),
        # Vimeo: the combined HTTPS file beats video-only HLS of the same height.
        ("vimeo", Preset.BEST, ("http-1080p", None)),
        ("vimeo", Preset.P480, ("http-360p", None)),
        # X: HTTPS beats the HLS rendition of the same height.
        ("twitter", Preset.BEST, ("http-2176", None)),
        # Reddit: the tallest is HLS-only at 640p; its audio has an HTTPS copy.
        ("reddit", Preset.BEST, ("hls-1875", "dash-AUDIO-1")),
        # ...and at 480 the higher-bitrate HTTPS rendition wins the tie.
        ("reddit", Preset.P480, ("dash-VIDEO-1", "dash-AUDIO-1")),
        # Twitch: two HLS 720p renditions; the higher bitrate wins the tie.
        ("twitch", Preset.BEST, ("720p-1", None)),
        ("twitch", Preset.MP3, (None, "Audio_Only")),
        ("dailymotion", Preset.P720, ("hls-720", None)),
        # SoundCloud: the plain HTTP MP3 beats its HLS twin at the same bitrate.
        ("soundcloud", Preset.MP3, (None, "http_mp3_0_0")),
    ],
)
def test_each_preset_on_each_site(site: str, preset: Preset, expected: tuple[str | None, str | None]) -> None:
    assert _picked(site, preset) == expected


def test_without_fragments_reddit_falls_back_to_its_https_formats() -> None:
    # Until the fragment path exists (#58), a plan must be fetchable by the engine.
    assert _picked("reddit", Preset.BEST, allow_fragmented=False) == ("dash-VIDEO-1", "dash-AUDIO-1")


def test_without_fragments_an_hls_only_site_has_no_plan() -> None:
    with pytest.raises(Error) as caught:
        select_plan(_formats("dailymotion"), Preset.BEST, allow_fragmented=False)

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY


def test_a_plan_says_whether_it_needs_the_fragment_path() -> None:
    assert select_plan(_formats("twitch"), Preset.BEST).fragmented is True
    assert select_plan(_formats("youtube"), Preset.BEST).fragmented is False


def test_a_combined_plan_is_a_video_with_its_container() -> None:
    plan = select_plan(_formats("vimeo"), Preset.BEST)

    assert (plan.kind, plan.mime_type, plan.extension, plan.quality) == (Kind.VIDEO, "video/mp4", "mp4", "1080p")


def test_an_mp3_plan_is_audio() -> None:
    plan = select_plan(_formats("soundcloud"), Preset.MP3)

    assert (plan.kind, plan.mime_type, plan.extension) == (Kind.AUDIO, "audio/mpeg", "mp3")


def test_video_presets_on_an_audio_only_site_are_refused() -> None:
    with pytest.raises(Error) as caught:
        select_plan(_formats("soundcloud"), Preset.BEST)

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY


# --- sizes --------------------------------------------------------------------


def test_exact_sizes_are_summed_and_are_not_an_estimate() -> None:
    plan = select_plan(_formats("youtube"), Preset.P1080)

    assert plan.expected_bytes == 80_911_999 + 3_449_447
    assert plan.size_is_estimate is False


def test_an_estimated_size_is_used_and_says_so() -> None:
    plan = select_plan(_formats("twitter"), Preset.BEST)

    assert (plan.expected_bytes, plan.size_is_estimate) == (862_240, True)


def test_an_unknown_size_is_unknown() -> None:
    assert select_plan(_formats("vimeo"), Preset.BEST).expected_bytes is None


# --- what a source offers -----------------------------------------------------


@pytest.mark.parametrize(
    ("site", "expected"),
    [
        ("youtube", [Preset.BEST, Preset.P2160, Preset.P1440, Preset.P1080, Preset.P720, Preset.P480, Preset.MP3]),
        # Vimeo's audio exists only inside the combined files and as HLS audio.
        ("vimeo", [Preset.BEST, Preset.P1080, Preset.P720, Preset.P480, Preset.MP3]),
        ("twitter", [Preset.BEST, Preset.P720, Preset.P480]),
        ("reddit", [Preset.BEST, Preset.P480, Preset.MP3]),
        ("twitch", [Preset.BEST, Preset.P720, Preset.P480, Preset.MP3]),
        ("dailymotion", [Preset.BEST, Preset.P1080, Preset.P720, Preset.P480]),
        # Audio only: MP3, and not a "best" that would mean something else.
        ("soundcloud", [Preset.MP3]),
    ],
)
def test_usable_presets(site: str, expected: list[Preset]) -> None:
    assert usable_presets(_formats(site)) == expected


def test_usable_presets_without_fragments() -> None:
    assert usable_presets(_formats("dailymotion"), allow_fragmented=False) == []
    assert usable_presets(_formats("vimeo"), allow_fragmented=False) == [
        Preset.BEST,
        Preset.P1080,
        Preset.P720,
        Preset.P480,
    ]


# --- the rule, on made-up formats ------------------------------------------------


def _video(format_id: str, height: int, *, audio: bool = False, size: int | None = None) -> Format:
    return Format(format_id, vcodec="avc1", acodec="mp4a" if audio else "none", ext="mp4", height=height, size=size)


def _audio(format_id: str, bitrate: int, *, size: int | None = None) -> Format:
    return Format(format_id, vcodec="none", acodec="mp4a", ext="m4a", bitrate=bitrate, size=size)


def test_mp3_takes_the_highest_bitrate_audio() -> None:
    plan = select_plan([_audio("139", 48_000), _audio("140", 128_000), _video("137", 1080)], Preset.MP3)

    assert (plan.audio.id if plan.audio else None, plan.video) == ("140", None)


def test_mp3_without_audio_is_refused() -> None:
    with pytest.raises(Error):
        select_plan([_video("137", 1080)], Preset.MP3)


def test_a_combined_format_wins_when_it_is_at_least_as_tall() -> None:
    plan = select_plan([_video("18", 720, audio=True), _video("136", 720), _audio("140", 128_000)], Preset.P720)

    assert (plan.video.id if plan.video else None, plan.audio) == ("18", None)


def test_video_and_audio_when_the_combined_format_is_shorter() -> None:
    plan = select_plan([_video("18", 360, audio=True), _video("137", 1080), _audio("140", 128_000)], Preset.P1080)

    assert (plan.video.id if plan.video else None, plan.audio.id if plan.audio else None) == ("137", "140")


def test_a_height_preset_never_exceeds_the_request() -> None:
    formats = [_video("313", 2160), _video("137", 1080), _video("135", 480), _audio("140", 128_000)]

    assert select_plan(formats, Preset.P1080).video == formats[1]


def test_everything_taller_than_the_target_falls_back_to_the_shortest() -> None:
    # Asking for 480p and being handed a 4K file is a bug, not a feature.
    formats = [_video("313", 2160), _video("137", 1080), _audio("140", 128_000)]

    assert select_plan(formats, Preset.P480).video == formats[1]


def test_video_only_without_any_audio_is_refused() -> None:
    with pytest.raises(Error):
        select_plan([_video("137", 1080)], Preset.P1080)


def test_one_unknown_part_size_makes_the_total_unknown() -> None:
    # Partial knowledge is worse than none: a total without the audio would
    # make the percentage overshoot and stall at 100.
    plan = select_plan([_video("137", 1080, size=80_000_000), _audio("140", 128_000)], Preset.P1080)

    assert plan.expected_bytes is None


# --- playback -----------------------------------------------------------------


def test_playback_is_capped_at_1080p() -> None:
    # Segments are transcoded as the player asks for them; 4K would cost a
    # great deal for nothing a browser player shows.
    formats = _formats("youtube")
    plan = playback_plan(formats)

    assert max(f.height or 0 for f in formats) > 1080
    assert plan == fetchable_plan(formats, Preset.P1080)
    assert plan.video is not None and plan.video.height == 1080
    assert plan.audio is not None


def test_playback_of_an_audio_only_site_is_its_audio() -> None:
    plan = playback_plan(_formats("soundcloud"))

    assert plan.video is None
    assert plan.audio is not None and plan.audio.id == "http_mp3_0_0"


def test_playback_of_a_streaming_only_site_is_refused_as_downloads_are() -> None:
    # One rule for both: #58 opens HLS and DASH to playback and download at once.
    with pytest.raises(Error) as caught:
        playback_plan(_formats("dailymotion"))

    assert caught.value.type == ErrorType.UNSUPPORTED_OPERATION
    assert "streaming formats" in (caught.value.message or "")
