import asyncio
from pathlib import Path

import pytest
from src.service.stream.session import SegmentState, StreamSession, StreamSessionStore


def _session(**overrides: object) -> StreamSession:
    base: dict[str, object] = {
        "id": "abc123",
        "source_url": "http://example.com/movie.mkv",
        "duration_seconds": 20.0,
        "has_video": True,
        "segment_seconds": 6,
        "session_dir": Path("/tmp/stream/abc123"),
        "encode_semaphore": asyncio.Semaphore(2),
    }
    base.update(overrides)
    return StreamSession(**base)  # ty: ignore[invalid-argument-type]


@pytest.mark.asyncio
async def test_segment_count_rounds_up() -> None:
    # 20s at 6s segments: 0-6, 6-12, 12-18, 18-20 = 4 segments
    assert _session(duration_seconds=20.0).segment_count == 4


@pytest.mark.asyncio
async def test_segment_count_is_at_least_one_for_a_degenerate_duration() -> None:
    assert _session(duration_seconds=0.0).segment_count == 1


@pytest.mark.asyncio
async def test_segment_duration_is_full_length_except_the_last() -> None:
    session = _session(duration_seconds=20.0, segment_seconds=6)
    assert session.segment_duration(0) == 6.0
    assert session.segment_duration(2) == 6.0
    assert session.segment_duration(3) == pytest.approx(2.0)


@pytest.mark.asyncio
async def test_segment_path_is_inside_the_session_dir() -> None:
    session = _session(session_dir=Path("/tmp/stream/abc123"))
    assert session.segment_path(3) == Path("/tmp/stream/abc123/segment_3.ts")


@pytest.mark.asyncio
async def test_state_of_defaults_to_not_started() -> None:
    assert _session().state_of(0) == SegmentState.NOT_STARTED


@pytest.mark.asyncio
async def test_event_for_returns_the_same_event_on_repeat_calls() -> None:
    session = _session()
    assert session.event_for(0) is session.event_for(0)


@pytest.mark.asyncio
async def test_touch_updates_last_accessed() -> None:
    session = _session()
    before = session.last_accessed
    session.touch()
    assert session.last_accessed >= before


@pytest.mark.asyncio
async def test_idle_seconds_uses_an_injected_now() -> None:
    session = _session()
    session.last_accessed = 100.0
    assert session.idle_seconds(now=130.0) == 30.0


@pytest.mark.asyncio
async def test_store_add_get_remove_all() -> None:
    store = StreamSessionStore()
    session = _session(id="s1")
    store.add(session)

    assert store.get("s1") is session
    assert store.all() == [session]

    removed = store.remove("s1")
    assert removed is session
    assert store.get("s1") is None
    assert store.all() == []


@pytest.mark.asyncio
async def test_store_remove_is_a_no_op_for_an_unknown_id() -> None:
    assert StreamSessionStore().remove("nope") is None


@pytest.mark.asyncio
async def test_info_hash_defaults_to_none() -> None:
    assert _session().info_hash is None


@pytest.mark.asyncio
async def test_info_hash_can_be_set() -> None:
    session = _session()
    session.info_hash = "abc123"
    assert session.info_hash == "abc123"


@pytest.mark.asyncio
async def test_status_defaults_to_ready() -> None:
    assert _session().status == "ready"


@pytest.mark.asyncio
async def test_status_and_error_can_be_set() -> None:
    session = _session()
    session.status = "connecting"
    session.error = None
    assert session.status == "connecting"

    session.status = "error"
    session.error = "no seeders"
    assert session.status == "error"
    assert session.error == "no seeders"
