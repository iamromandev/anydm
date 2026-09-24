import asyncio
import time
from pathlib import Path

import pytest
from src.core.error import Error
from src.lib.media.ffmpeg import mp3_args, mux_args, remux_args, run, segment_args
from src.lib.media.source import MediaInput, PlaylistCut


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


def test_mux_args_moves_an_index_only_where_there_is_one() -> None:
    # `+faststart` is the MP4 muxer's; WebM and MKV have nothing to move.
    for container in ("webm", "mkv"):
        args = mux_args("ffmpeg", Path("/t/v.part"), Path("/t/a.part"), Path(f"/t/out.{container}"))
        assert "-movflags" not in args
        assert args[-1] == f"/t/out.{container}"


def test_mux_args_overwrites_without_prompting() -> None:
    assert "-y" in mux_args("ffmpeg", Path("/t/v"), Path("/t/a"), Path("/t/o"))


def test_mp3_args_drop_video_and_encode_audio() -> None:
    args = mp3_args("ffmpeg", Path("/t/a.part"), Path("/t/out.mp3"))
    assert "-vn" in args
    assert args[args.index("-c:a") + 1] == "libmp3lame"
    assert args[args.index("-q:a") + 1] == "2"
    assert args[-1] == "/t/out.mp3"


def test_remux_args_copy_every_stream_into_the_destination_s_container() -> None:
    mp4 = remux_args("ffmpeg", Path("/t/video.part"), Path("/t/out.mp4"))
    assert mp4[mp4.index("-i") + 1] == "/t/video.part"
    assert mp4[mp4.index("-c") + 1] == "copy"
    assert mp4[mp4.index("-movflags") + 1] == "+faststart"
    assert mp4[-1] == "/t/out.mp4"

    mkv = remux_args("ffmpeg", Path("/t/video.part"), Path("/t/out.mkv"))
    assert "-movflags" not in mkv


def test_the_configured_binary_is_used() -> None:
    assert mp3_args("/opt/bin/ffmpeg", Path("/t/a"), Path("/t/o"))[0] == "/opt/bin/ffmpeg"


def test_segment_args_seeks_and_bounds_a_video_segment() -> None:
    args = segment_args(
        "ffmpeg", [MediaInput("http://example.com/movie.mkv")], 12.0, 6.0, Path("/t/segment_2.ts"), has_video=True
    )
    assert args[0] == "ffmpeg"
    assert args[args.index("-ss") + 1] == "12.0"
    assert args[args.index("-i") + 1] == "http://example.com/movie.mkv"
    assert args[args.index("-t") + 1] == "6.0"
    assert args[args.index("-c:v") + 1] == "libx264"
    assert args[args.index("-c:a") + 1] == "aac"
    assert args[args.index("-f") + 1] == "mpegts"
    assert args[-1] == "/t/segment_2.ts"


def test_the_first_segment_reads_from_the_start_rather_than_seeking_to_it() -> None:
    # ffmpeg's HLS demuxer drops packets until a keyframe at or past the seek
    # target. Dailymotion's first keyframe decodes 0.03 s before the stream's
    # start, so seeking to 0 dropped it: three seconds without a picture.
    inputs = [MediaInput("https://media.test/v"), MediaInput("https://media.test/a")]
    args = segment_args("ffmpeg", inputs, 0.0, 6.0, Path("/t/segment_0.ts"), has_video=True)

    assert "-ss" not in args
    assert [args[i + 1] for i, arg in enumerate(args) if arg == "-i"] == ["https://media.test/v", "https://media.test/a"]


def test_segment_args_does_not_offset_output_timestamps() -> None:
    # Deliberately not offset — see the docstring on segment_args(). The
    # playlist's #EXT-X-DISCONTINUITY markers are what handle this instead.
    args = segment_args(
        "ffmpeg", [MediaInput("http://example.com/movie.mkv")], 12.0, 6.0, Path("/t/segment_2.ts"), has_video=True
    )
    assert "-output_ts_offset" not in args


def test_segment_args_drops_video_flags_for_audio_only() -> None:
    args = segment_args(
        "ffmpeg", [MediaInput("http://example.com/song.mp3")], 6.0, 6.0, Path("/t/segment_1.ts"), has_video=False
    )
    assert "-vn" in args
    assert "-c:v" not in args
    assert args[args.index("-c:a") + 1] == "aac"


def test_segment_args_uses_the_configured_binary() -> None:
    args = segment_args("/opt/bin/ffmpeg", [MediaInput("http://x/y.mp4")], 0.0, 6.0, Path("/t/o.ts"), has_video=True)
    assert args[0] == "/opt/bin/ffmpeg"


def test_segment_args_downmixes_audio_to_stereo() -> None:
    # A multichannel (e.g. 5.1) source re-encoded to multichannel AAC
    # reliably fails to append into Chromium's MediaSource. Stereo is the
    # safe, universally-supported target — see the docstring on segment_args().
    video = segment_args(
        "ffmpeg", [MediaInput("http://example.com/movie.mkv")], 0.0, 6.0, Path("/t/s.ts"), has_video=True
    )
    assert video[video.index("-ac") + 1] == "2"

    audio_only = segment_args(
        "ffmpeg", [MediaInput("http://example.com/song.flac")], 0.0, 6.0, Path("/t/s.ts"), has_video=False
    )
    assert audio_only[audio_only.index("-ac") + 1] == "2"


def test_segment_args_sends_no_headers_and_maps_nothing_for_one_plain_input() -> None:
    args = segment_args("ffmpeg", [MediaInput("http://x/y.mp4")], 0.0, 6.0, Path("/t/o.ts"), has_video=True)
    assert "-headers" not in args
    assert "-map" not in args


def test_segment_args_gives_each_input_its_own_seek_and_headers() -> None:
    # An input option applies only to the -i that follows it, so the audio
    # input needs its own -ss, or its segment would always start at zero.
    video = MediaInput("https://media.test/v", {"User-Agent": "UA", "Referer": "https://site.test/"})
    audio = MediaInput("https://media.test/a", {"User-Agent": "UA"})
    args = segment_args("ffmpeg", [video, audio], 12.0, 6.0, Path("/t/segment_2.ts"), has_video=True)

    inputs = [i for i, arg in enumerate(args) if arg == "-i"]
    assert [args[i + 1] for i in inputs] == ["https://media.test/v", "https://media.test/a"]
    for i, headers in zip(inputs, ["User-Agent: UA\r\nReferer: https://site.test/\r\n", "User-Agent: UA\r\n"], strict=True):
        assert args[i - 2 : i] == ["-headers", headers]
        assert args[i - 4 : i - 2] == ["-ss", "12.0"]


def test_segment_args_takes_video_from_the_first_input_and_audio_from_the_second() -> None:
    args = segment_args(
        "ffmpeg", [MediaInput("https://media.test/v"), MediaInput("https://media.test/a")],
        0.0, 6.0, Path("/t/o.ts"), has_video=True,
    )
    maps = [args[i + 1] for i, arg in enumerate(args) if arg == "-map"]
    assert maps == ["0:v:0", "1:a:0"]
    assert args.index("-map") > max(i for i, arg in enumerate(args) if arg == "-i")


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


def _inputs(args: list[str]) -> list[str]:
    return [args[i + 1] for i, arg in enumerate(args) if arg == "-i"]


def test_plain_inputs_come_out_as_they_always_have() -> None:
    # Pins today's arguments for plain files while cuts arrive beside them.
    video = MediaInput("https://media.test/v", {"User-Agent": "UA"})
    audio = MediaInput("https://media.test/a")

    assert segment_args("ffmpeg", [video, audio], 12.0, 6.0, Path("/t/s.ts"), has_video=True) == [
        "ffmpeg", "-y",
        "-ss", "12.0", "-headers", "User-Agent: UA\r\n", "-i", "https://media.test/v",
        "-ss", "12.0", "-i", "https://media.test/a",
        "-t", "6.0",
        "-map", "0:v:0", "-map", "1:a:0",
        "-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac", "-ac", "2",
        "-f", "mpegts", "/t/s.ts",
    ]


def test_a_cut_is_read_whole_and_trimmed_on_the_way_out() -> None:
    # Seeking into HLS drops packets until a keyframe past the target (#87),
    # so a cut's fragments are read from their start and the output trimmed.
    cut = PlaylistCut(Path("/s/segment_2.0.m3u8"), starts_at=10.0)
    args = segment_args("ffmpeg", [cut], 12.0, 6.0, Path("/s/segment_2.ts"), has_video=True)

    i = args.index("-i")
    assert args[i + 1] == "/s/segment_2.0.m3u8"
    assert args[i - 6 : i] == [
        "-itsoffset", "0.000",
        "-protocol_whitelist", "file,http,https,tcp,tls,crypto",
        "-allowed_extensions", "ALL",
    ]
    assert args[i + 2 : i + 6] == ["-ss", "2.000", "-t", "6.0"]
    assert args.count("-ss") == 1
    # ffmpeg can't pass headers on from a local playlist to its fragments.
    assert "-headers" not in args


def test_two_cuts_line_up_on_the_earlier_one() -> None:
    # Dailymotion's video fragments run 3.00 s and its audio's 2.90 s, so a
    # segment's first video and audio fragments rarely start together.
    video = PlaylistCut(Path("/s/segment_10.0.m3u8"), starts_at=60.0)
    audio = PlaylistCut(Path("/s/segment_10.1.m3u8"), starts_at=58.0)
    args = segment_args("ffmpeg", [video, audio], 60.0, 6.0, Path("/s/segment_10.ts"), has_video=True)

    assert [args[i + 1] for i, arg in enumerate(args) if arg == "-itsoffset"] == ["2.000", "0.000"]
    assert _inputs(args) == ["/s/segment_10.0.m3u8", "/s/segment_10.1.m3u8"]
    assert args[args.index("-ss") + 1] == "2.000"
    assert args.index("-ss") > max(i for i, arg in enumerate(args) if arg == "-i")
    assert [args[i + 1] for i, arg in enumerate(args) if arg == "-map"] == ["0:v:0", "1:a:0"]


def test_a_cut_of_audio_alone_is_encoded_as_audio() -> None:
    args = segment_args("ffmpeg", [PlaylistCut(Path("/s/c.m3u8"), 0.0)], 0.0, 6.0, Path("/s/o.ts"), has_video=False)

    assert "-vn" in args
    assert "-c:v" not in args


def test_plain_inputs_and_cuts_are_never_mixed() -> None:
    inputs = [MediaInput("https://media.test/v"), PlaylistCut(Path("/s/a.m3u8"), 0.0)]

    with pytest.raises(ValueError):
        segment_args("ffmpeg", inputs, 6.0, 6.0, Path("/s/o.ts"), has_video=True)  # ty: ignore[invalid-argument-type]
