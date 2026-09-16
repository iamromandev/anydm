from pathlib import Path

import pytest
from src.lib.media.ffprobe import ProbeResult
from src.service.stream.session import StreamSessionStore
from src.service.stream.stream_service import StreamService
from src.service.stream.sweeper import StreamIdleSweeper


async def _fake_prober(_ffprobe: str, _source: str) -> ProbeResult:
    return ProbeResult(duration_seconds=12.0, has_video=True)


async def _fake_encoder(args: list[str]) -> None:
    Path(args[-1]).write_bytes(b"x")


@pytest.mark.asyncio
async def test_sweep_removes_sessions_past_the_idle_timeout(tmp_path: Path) -> None:
    sessions = StreamSessionStore()
    service = StreamService(
        sessions=sessions,
        stream_dir=tmp_path,
        ffmpeg_path="ffmpeg",
        ffprobe_path="ffprobe",
        segment_seconds=6,
        readahead_segments=0,
        max_concurrent_encodes=2,
        prober=_fake_prober,
        encoder=_fake_encoder,
    )
    session = await service.start_session("http://example.com/a.mp4")
    session.last_accessed = 0.0  # far in the past relative to the injected clock

    sweeper = StreamIdleSweeper(
        service=service,
        sessions=sessions,
        idle_timeout_s=300,
        clock=lambda: 1000.0,
    )
    await sweeper.sweep()

    assert sessions.get(session.id) is None


@pytest.mark.asyncio
async def test_sweep_keeps_recently_accessed_sessions(tmp_path: Path) -> None:
    sessions = StreamSessionStore()
    service = StreamService(
        sessions=sessions,
        stream_dir=tmp_path,
        ffmpeg_path="ffmpeg",
        ffprobe_path="ffprobe",
        segment_seconds=6,
        readahead_segments=0,
        max_concurrent_encodes=2,
        prober=_fake_prober,
        encoder=_fake_encoder,
    )
    session = await service.start_session("http://example.com/a.mp4")
    session.last_accessed = 990.0

    sweeper = StreamIdleSweeper(
        service=service,
        sessions=sessions,
        idle_timeout_s=300,
        clock=lambda: 1000.0,
    )
    await sweeper.sweep()

    assert sessions.get(session.id) is session


@pytest.mark.asyncio
async def test_start_and_stop_the_background_loop(tmp_path: Path) -> None:
    sessions = StreamSessionStore()
    service = StreamService(
        sessions=sessions,
        stream_dir=tmp_path,
        ffmpeg_path="ffmpeg",
        ffprobe_path="ffprobe",
        segment_seconds=6,
        readahead_segments=0,
        max_concurrent_encodes=2,
        prober=_fake_prober,
        encoder=_fake_encoder,
    )
    sweeper = StreamIdleSweeper(service=service, sessions=sessions, idle_timeout_s=300, poll_s=0.01)

    await sweeper.start()
    await sweeper.stop()  # must return cleanly, not hang or raise
