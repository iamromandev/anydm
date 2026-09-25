import json

import pytest
from src.core.error import Error
from src.core.type import ErrorType
from src.lib.media.audio import AudioTrack
from src.lib.media.ffprobe import ProbeResult, capture, parse_probe_output, probe_args
from src.lib.media.subtitle import SubtitleTrack


def test_probe_args_requests_json_format_and_streams() -> None:
    args = probe_args("ffprobe", "http://example.com/movie.mkv")
    assert args[0] == "ffprobe"
    assert "-show_format" in args
    assert "-show_streams" in args
    assert args[args.index("-print_format") + 1] == "json"
    assert args[-1] == "http://example.com/movie.mkv"


def test_probe_args_uses_the_configured_binary() -> None:
    assert probe_args("/opt/bin/ffprobe", "http://x/y.mp4")[0] == "/opt/bin/ffprobe"


def test_probe_args_sends_headers_only_when_there_are_some() -> None:
    assert "-headers" not in probe_args("ffprobe", "http://x/y.mp4")

    args = probe_args("ffprobe", "https://media.test/v", {"User-Agent": "UA", "Referer": "https://site.test/"})
    assert args[-3:] == ["-headers", "User-Agent: UA\r\nReferer: https://site.test/\r\n", "https://media.test/v"]


@pytest.mark.asyncio
async def test_capture_returns_stdout_on_success() -> None:
    stdout = await capture(["python3", "-c", "print('hello')"])
    assert stdout.strip() == "hello"


@pytest.mark.asyncio
async def test_capture_raises_on_a_non_zero_exit() -> None:
    with pytest.raises(Error) as caught:
        await capture(["python3", "-c", "import sys; sys.stderr.write('boom'); sys.exit(1)"])
    assert caught.value.message is not None
    assert "boom" in caught.value.message


@pytest.mark.asyncio
async def test_capture_times_out_when_the_process_runs_too_long() -> None:
    with pytest.raises(Error) as caught:
        await capture(
            ["python3", "-c", "import time; time.sleep(5)"],
            timeout_s=0.1,
        )
    assert caught.value.type == ErrorType.TIMEOUT


@pytest.mark.asyncio
async def test_capture_reports_a_missing_binary() -> None:
    with pytest.raises(Error) as caught:
        await capture(["definitely-not-a-real-binary-xyz"])
    assert caught.value.retry_able is False


def test_parse_probe_output_reads_duration_and_detects_video() -> None:
    raw = json.dumps({
        "format": {"duration": "125.480000"},
        "streams": [
            {"codec_type": "video"},
            {"codec_type": "audio"},
        ],
    })
    result = parse_probe_output(raw)
    assert result == ProbeResult(duration_seconds=125.48, has_video=True, audio_tracks=(AudioTrack(0),))


def test_parse_probe_output_detects_audio_only() -> None:
    raw = json.dumps({
        "format": {"duration": "200.0"},
        "streams": [{"codec_type": "audio"}],
    })
    assert parse_probe_output(raw).has_video is False


def test_parse_probe_output_raises_on_malformed_json() -> None:
    with pytest.raises(Error):
        parse_probe_output("not json")


def test_parse_probe_output_raises_when_duration_is_missing() -> None:
    with pytest.raises(Error):
        parse_probe_output(json.dumps({"format": {}, "streams": []}))


def test_parse_probe_output_reads_the_container_and_codecs() -> None:
    """What decides whether a browser can play the file itself (#94)."""
    raw = json.dumps({
        "format": {"duration": "20.0", "format_name": "matroska,webm"},
        "streams": [
            {"codec_type": "video", "codec_name": "h264"},
            {"codec_type": "audio", "codec_name": "aac", "disposition": {"default": 0}},
            {"codec_type": "audio", "codec_name": "ac3", "disposition": {"default": 1}},
            {"codec_type": "subtitle", "codec_name": "subrip"},
        ],
    })
    result = parse_probe_output(raw)
    assert result.container == "matroska,webm"
    assert result.video_codec == "h264"
    # The track a player opens with: the one flagged default, not the first.
    assert result.audio_codec == "ac3"


def test_parse_probe_output_takes_the_first_audio_track_when_none_is_default() -> None:
    raw = json.dumps({
        "format": {"duration": "20.0", "format_name": "mp3"},
        "streams": [{"codec_type": "audio", "codec_name": "mp3"}, {"codec_type": "audio", "codec_name": "aac"}],
    })
    result = parse_probe_output(raw)
    assert (result.video_codec, result.audio_codec) == (None, "mp3")


def test_parse_probe_output_ignores_a_cover_picture() -> None:
    """An MP3's or M4A's cover art is a one-frame video stream, not a picture to play."""
    raw = json.dumps({
        "format": {"duration": "200.0", "format_name": "mp3"},
        "streams": [
            {"codec_type": "audio", "codec_name": "mp3"},
            {"codec_type": "video", "codec_name": "mjpeg", "disposition": {"attached_pic": 1}},
        ],
    })
    result = parse_probe_output(raw)
    assert result.has_video is False
    assert result.video_codec is None


def test_parse_probe_output_lists_every_audio_track() -> None:
    """Counted among audio streams only, as ``-map 0:a:N`` counts them (#99)."""
    raw = json.dumps({
        "format": {"duration": "20.0", "format_name": "matroska,webm"},
        "streams": [
            {"codec_type": "video", "codec_name": "h264"},
            {
                "codec_type": "audio", "codec_name": "ac3", "channels": 6,
                "disposition": {"default": 1}, "tags": {"language": "spa", "title": "Doblaje"},
            },
            {"codec_type": "subtitle", "codec_name": "subrip", "tags": {"language": "eng"}},
            {"codec_type": "audio", "codec_name": "aac", "channels": 2, "tags": {"language": "eng"}},
        ],
    })
    assert parse_probe_output(raw).audio_tracks == (
        AudioTrack(0, language="spa", title="Doblaje", channels=6, codec="ac3", default=True),
        AudioTrack(1, language="eng", channels=2, codec="aac"),
    )


def test_parse_probe_output_lists_no_audio_tracks_for_a_silent_file() -> None:
    raw = json.dumps({"format": {"duration": "4.0"}, "streams": [{"codec_type": "video"}]})
    assert parse_probe_output(raw).audio_tracks == ()


def test_parse_probe_output_lists_every_subtitle_track() -> None:
    """Counted among subtitle streams only, as ``-map 0:s:N`` counts them (#100)."""
    raw = json.dumps({
        "format": {"duration": "20.0"},
        "streams": [
            {"codec_type": "video", "codec_name": "h264"},
            {"codec_type": "subtitle", "codec_name": "subrip", "tags": {"language": "eng", "title": "English"}},
            {"codec_type": "audio", "codec_name": "aac"},
            {"codec_type": "subtitle", "codec_name": "ass", "disposition": {"default": 1}},
            {"codec_type": "subtitle", "codec_name": "hdmv_pgs_subtitle", "disposition": {"forced": 1},
             "tags": {"language": "eng"}},
        ],
    })
    tracks = parse_probe_output(raw).subtitle_tracks

    assert tracks == (
        SubtitleTrack(0, language="eng", title="English", codec="subrip"),
        SubtitleTrack(1, codec="ass", default=True),
        SubtitleTrack(2, language="eng", codec="hdmv_pgs_subtitle", forced=True),
    )
    assert [track.text for track in tracks] == [True, True, False]
