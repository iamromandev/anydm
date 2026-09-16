import asyncio
import time
from pathlib import Path

import pytest
from src.core.error import Error
from src.lib.media.ffmpeg import mp3_args, mux_args, run, segment_args


def test_mux_args_copies_both_streams_without_re_encoding() -> None:
    args = mux_args("ffmpeg", Path("/t/v.part"), Path("/t/a.part"), Path("/t/out.mp4"))
    assert args[0] == "ffmpeg"
    assert args.count("-i") == 2
    assert args[args.index("-i") + 1] == "/t/v.part"
    assert "-c" in args
    assert args[args.index("-c") + 1] == "copy"
    assert args[-1] == "/t/out.mp4"


def test_mux_args_moves_the_index_to_the_front() -> None:
    args = mux_args("ffmpeg", Path("/t/v.part"), Path("/t/a.part"), Path("/t/out.mp4"))
    assert "-movflags" in args
    assert args[args.index("-movflags") + 1] == "+faststart"


def test_mux_args_overwrites_without_prompting() -> None:
    assert "-y" in mux_args("ffmpeg", Path("/t/v"), Path("/t/a"), Path("/t/o"))


def test_mp3_args_drop_video_and_encode_audio() -> None:
    args = mp3_args("ffmpeg", Path("/t/a.part"), Path("/t/out.mp3"))
    assert "-vn" in args
    assert args[args.index("-c:a") + 1] == "libmp3lame"
    assert args[args.index("-q:a") + 1] == "2"
    assert args[-1] == "/t/out.mp3"


def test_the_configured_binary_is_used() -> None:
    assert mp3_args("/opt/bin/ffmpeg", Path("/t/a"), Path("/t/o"))[0] == "/opt/bin/ffmpeg"


def test_segment_args_seeks_and_bounds_a_video_segment() -> None:
    args = segment_args(
        "ffmpeg", "http://example.com/movie.mkv", 12.0, 6.0, Path("/t/segment_2.ts"), has_video=True
    )
    assert args[0] == "ffmpeg"
    assert args[args.index("-ss") + 1] == "12.0"
    assert args[args.index("-i") + 1] == "http://example.com/movie.mkv"
    assert args[args.index("-t") + 1] == "6.0"
    assert args[args.index("-c:v") + 1] == "libx264"
    assert args[args.index("-c:a") + 1] == "aac"
    assert args[args.index("-f") + 1] == "mpegts"
    assert args[-1] == "/t/segment_2.ts"


def test_segment_args_does_not_offset_output_timestamps() -> None:
    # Deliberately not offset — see the docstring on segment_args(). The
    # playlist's #EXT-X-DISCONTINUITY markers are what handle this instead.
    args = segment_args(
        "ffmpeg", "http://example.com/movie.mkv", 12.0, 6.0, Path("/t/segment_2.ts"), has_video=True
    )
    assert "-output_ts_offset" not in args


def test_segment_args_drops_video_flags_for_audio_only() -> None:
    args = segment_args(
        "ffmpeg", "http://example.com/song.mp3", 6.0, 6.0, Path("/t/segment_1.ts"), has_video=False
    )
    assert "-vn" in args
    assert "-c:v" not in args
    assert args[args.index("-c:a") + 1] == "aac"


def test_segment_args_uses_the_configured_binary() -> None:
    args = segment_args("/opt/bin/ffmpeg", "http://x/y.mp4", 0.0, 6.0, Path("/t/o.ts"), has_video=True)
    assert args[0] == "/opt/bin/ffmpeg"


def test_segment_args_downmixes_audio_to_stereo() -> None:
    # A multichannel (e.g. 5.1) source re-encoded to multichannel AAC
    # reliably fails to append into Chromium's MediaSource. Stereo is the
    # safe, universally-supported target — see the docstring on segment_args().
    video = segment_args(
        "ffmpeg", "http://example.com/movie.mkv", 0.0, 6.0, Path("/t/s.ts"), has_video=True
    )
    assert video[video.index("-ac") + 1] == "2"

    audio_only = segment_args(
        "ffmpeg", "http://example.com/song.flac", 0.0, 6.0, Path("/t/s.ts"), has_video=False
    )
    assert audio_only[audio_only.index("-ac") + 1] == "2"


@pytest.mark.asyncio
async def test_run_raises_on_a_non_zero_exit() -> None:
    with pytest.raises(Error) as caught:
        await run(["python3", "-c", "import sys; sys.stderr.write('boom'); sys.exit(1)"])
    assert caught.value.message is not None
    assert "boom" in caught.value.message


@pytest.mark.asyncio
async def test_a_non_zero_exit_is_retryable() -> None:
    with pytest.raises(Error) as caught:
        await run(["python3", "-c", "import sys; sys.exit(1)"])
    assert caught.value.retry_able is True


@pytest.mark.asyncio
async def test_run_succeeds_quietly_on_a_zero_exit() -> None:
    await run(["python3", "-c", "pass"])


@pytest.mark.asyncio
async def test_run_reports_a_missing_binary_permanently() -> None:
    with pytest.raises(Error) as caught:
        await run(["definitely-not-a-real-binary-xyz"])
    assert caught.value.retry_able is False


@pytest.mark.asyncio
async def test_run_kills_the_subprocess_when_its_task_is_cancelled() -> None:
    # A long-lived process, cancelled almost immediately. If cancelling the
    # task only unblocked `await` without killing the subprocess, this would
    # hang for the full 10 seconds instead of returning promptly.
    task = asyncio.create_task(run(["python3", "-c", "import time; time.sleep(10)"]))
    await asyncio.sleep(0.05)

    started = time.monotonic()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert time.monotonic() - started < 5.0
