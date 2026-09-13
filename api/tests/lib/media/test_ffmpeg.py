from pathlib import Path

import pytest
from src.core.error import Error
from src.lib.media.ffmpeg import mp3_args, mux_args, run


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
