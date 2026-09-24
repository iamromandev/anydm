import json

import pytest
from src.core.error import Error
from src.core.type import ErrorType
from src.lib.media.ffprobe import ProbeResult, capture, parse_probe_output, probe_args


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
    assert result == ProbeResult(duration_seconds=125.48, has_video=True)


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
