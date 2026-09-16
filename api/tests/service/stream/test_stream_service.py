import asyncio
from pathlib import Path

import pytest
from src.core.error import Error
from src.lib.media.ffprobe import ProbeResult
from src.service.stream.session import SegmentState, StreamSessionStore
from src.service.stream.stream_service import StreamService


def _service(tmp_path: Path, **overrides: object) -> tuple[StreamService, list[list[str]]]:
    encoded_calls: list[list[str]] = []

    async def fake_prober(_ffprobe: str, _source: str) -> ProbeResult:
        return ProbeResult(duration_seconds=20.0, has_video=True)

    async def fake_encoder(args: list[str]) -> None:
        encoded_calls.append(args)
        Path(args[-1]).write_bytes(b"fake-ts-data")

    defaults: dict[str, object] = {
        "sessions": StreamSessionStore(),
        "stream_dir": tmp_path,
        "ffmpeg_path": "ffmpeg",
        "ffprobe_path": "ffprobe",
        "segment_seconds": 6,
        "readahead_segments": 2,
        "max_concurrent_encodes": 2,
        "prober": fake_prober,
        "encoder": fake_encoder,
    }
    defaults.update(overrides)
    return StreamService(**defaults), encoded_calls


@pytest.mark.asyncio
async def test_start_session_probes_and_creates_a_session_dir(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")

    assert session.duration_seconds == 20.0
    assert session.has_video is True
    assert session.segment_count == 4
    assert session.session_dir.exists()
    assert service.get_session(session.id) is session


@pytest.mark.asyncio
async def test_get_session_raises_not_found_for_an_unknown_id(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    with pytest.raises(Error):
        service.get_session("nope")


@pytest.mark.asyncio
async def test_playlist_text_lists_every_segment_with_the_right_durations(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")
    playlist = service.playlist_text(session)

    assert "#EXTM3U" in playlist
    assert "#EXT-X-ENDLIST" in playlist
    assert "segment_0.ts" in playlist
    assert "segment_3.ts" in playlist
    assert "segment_4.ts" not in playlist
    assert "#EXTINF:2.000," in playlist  # the shorter final segment


@pytest.mark.asyncio
async def test_playlist_text_marks_every_segment_after_the_first_as_a_discontinuity(
    tmp_path: Path,
) -> None:
    service, _ = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")
    lines = service.playlist_text(session).splitlines()

    # segment_0.ts is the start of the stream and needs no discontinuity
    # marker; every segment after it is an independently-encoded file whose
    # raw timestamps restart near zero, so each needs one.
    assert lines[lines.index("segment_0.ts") - 2] != "#EXT-X-DISCONTINUITY"
    for name in ["segment_1.ts", "segment_2.ts", "segment_3.ts"]:
        assert lines[lines.index(name) - 2] == "#EXT-X-DISCONTINUITY"


@pytest.mark.asyncio
async def test_get_segment_generates_and_returns_the_file(tmp_path: Path) -> None:
    service, encoded_calls = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")

    path = await service.get_segment(session, 0)

    assert path == session.segment_path(0)
    assert path.exists()
    assert session.state_of(0) == SegmentState.READY
    assert len(encoded_calls) >= 1


@pytest.mark.asyncio
async def test_get_segment_rejects_an_out_of_range_index(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")
    with pytest.raises(Error):
        await service.get_segment(session, 999)


@pytest.mark.asyncio
async def test_concurrent_requests_for_the_same_segment_encode_only_once(tmp_path: Path) -> None:
    encode_started = asyncio.Event()
    release_encode = asyncio.Event()
    calls: list[list[str]] = []

    async def slow_prober(_ffprobe: str, _source: str) -> ProbeResult:
        return ProbeResult(duration_seconds=20.0, has_video=True)

    async def slow_encoder(args: list[str]) -> None:
        calls.append(args)
        encode_started.set()
        await release_encode.wait()
        Path(args[-1]).write_bytes(b"fake-ts-data")

    service = StreamService(
        sessions=StreamSessionStore(),
        stream_dir=tmp_path,
        ffmpeg_path="ffmpeg",
        ffprobe_path="ffprobe",
        segment_seconds=6,
        readahead_segments=0,
        max_concurrent_encodes=2,
        prober=slow_prober,
        encoder=slow_encoder,
    )
    session = await service.start_session("http://example.com/movie.mkv")

    task_a = asyncio.create_task(service.get_segment(session, 0))
    await encode_started.wait()
    task_b = asyncio.create_task(service.get_segment(session, 0))
    await asyncio.sleep(0.01)  # let task_b reach the GENERATING branch and start waiting
    release_encode.set()

    await asyncio.gather(task_a, task_b)
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_requesting_a_segment_triggers_readahead(tmp_path: Path) -> None:
    service, _encoded_calls = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")

    await service.get_segment(session, 0)
    await asyncio.gather(*session.background_tasks)

    assert session.state_of(1) == SegmentState.READY
    assert session.state_of(2) == SegmentState.READY
    assert session.state_of(3) == SegmentState.NOT_STARTED  # readahead is 2, not the whole file


@pytest.mark.asyncio
async def test_stop_session_removes_it_and_deletes_its_directory(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    session = await service.start_session("http://example.com/movie.mkv")
    await service.get_segment(session, 0)
    session_dir = session.session_dir

    await service.stop_session(session.id)

    assert not session_dir.exists()
    with pytest.raises(Error):
        service.get_session(session.id)


@pytest.mark.asyncio
async def test_stop_session_on_an_unknown_id_is_a_no_op(tmp_path: Path) -> None:
    service, _ = _service(tmp_path)
    await service.stop_session("nope")  # must not raise


@pytest.mark.asyncio
async def test_stop_session_does_not_hang_on_a_still_running_readahead_encode(
    tmp_path: Path,
) -> None:
    # Regression: stop_session used to fire-and-forget cancel() without
    # awaiting the cancelled tasks, then immediately rmtree the directory
    # those tasks were still writing into. On a slow filesystem that can hang
    # the whole process. The fake encoder here never returns on its own —
    # only cancellation ends it — so this proves stop_session actually waits.
    async def fake_prober(_ffprobe: str, _source: str) -> ProbeResult:
        return ProbeResult(duration_seconds=20.0, has_video=True)

    never_finishes = asyncio.Event()

    async def hanging_encoder(args: list[str]) -> None:
        destination = Path(args[-1])
        destination.touch()
        # segment_0 (the directly-awaited one) must complete normally so
        # get_segment() returns and the readahead tasks actually get a
        # chance to start; only the readahead segments hang.
        if destination.name != "segment_0.ts":
            await never_finishes.wait()  # only cancellation ends this

    service = StreamService(
        sessions=StreamSessionStore(),
        stream_dir=tmp_path,
        ffmpeg_path="ffmpeg",
        ffprobe_path="ffprobe",
        segment_seconds=6,
        readahead_segments=2,
        max_concurrent_encodes=2,
        prober=fake_prober,
        encoder=hanging_encoder,
    )
    session = await service.start_session("http://example.com/movie.mkv")

    await service.get_segment(session, 0)  # kicks off readahead for 1 and 2
    await asyncio.sleep(0.01)  # let the readahead tasks actually start

    await asyncio.wait_for(service.stop_session(session.id), timeout=2.0)

    assert all(task.cancelled() for task in session.background_tasks)
