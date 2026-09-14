import uuid
from pathlib import Path
from typing import Any, cast

import pytest
from src.lib.event import EventHub
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker
from src.service.download.progress import AggregateSample, SegmentProgress


class FakeTaskRepo:
    def __init__(self) -> None:
        self.progress: list[dict[str, Any]] = []

    async def flush_progress(self, task_id: uuid.UUID, **kwargs: Any) -> None:
        self.progress.append(kwargs)


class FakeSegmentRepo:
    def __init__(self) -> None:
        self.flushed: list[tuple[str, dict[int, int]]] = []

    async def flush(self, task_id: uuid.UUID, part: str, watermarks: Any) -> None:
        self.flushed.append((part, dict(watermarks)))


def _worker(
    hub: EventHub | None = None,
    repo: Any = None,
    segment_repo: Any = None,
    segments: int = 4,
) -> DownloadWorker:
    # ``_flush`` and ``_segment_count`` touch none of these, and building the
    # real ones would drag a database and an HTTP client into a test about
    # arithmetic and a dictionary.
    stub = cast(Any, object())
    return DownloadWorker(
        name="test",
        repo=cast(Any, repo or FakeTaskRepo()),
        segment_repo=cast(Any, segment_repo or FakeSegmentRepo()),
        client=stub,
        engine=stub,
        post_processor=stub,
        control=DownloadControl(),
        hub=hub or EventHub(),
        downloads_root=Path("/tmp"),
        max_attempts=3,
        segments=segments,
    )


def test_the_count_halves_on_every_attempt_of_a_fresh_part() -> None:
    """A server that 429s under four connections is fine with one."""
    worker = _worker()
    assert worker._segment_count(1, 0) == 4
    assert worker._segment_count(2, 0) == 2
    assert worker._segment_count(3, 0) == 1
    assert worker._segment_count(9, 0) == 1


def test_bytes_on_disk_outrank_the_backoff() -> None:
    """Crash recovery bumps `attempts` without any failure having happened.

    Backing off there would plan a different set of ranges, which reconcile
    cannot match, so the whole partial `.part` would be thrown away — a full
    re-download on every process restart.
    """
    worker = _worker()
    assert worker._segment_count(2, 2496) == 4
    assert worker._segment_count(9, 1) == 4


def _sample(segments: tuple[SegmentProgress, ...]) -> AggregateSample:
    return AggregateSample(
        downloaded_bytes=300,
        total_bytes=1000,
        progress=30,
        speed_bps=99,
        eta_seconds=7,
        segments=segments,
    )


@pytest.mark.asyncio
async def test_a_segmented_flush_publishes_every_segment() -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    repo, segment_repo = FakeTaskRepo(), FakeSegmentRepo()
    worker = _worker(hub, repo, segment_repo)
    task_id = uuid.uuid4()

    await worker._flush(
        task_id,
        "file",
        _sample((SegmentProgress(0, 0, 499, 200, 60), SegmentProgress(1, 500, 999, 100, 39))),
        offset=0,
        total=1000,
    )

    event, data = await anext(aiter(subscription))
    assert event == "progress"
    assert data["progress"] == 30
    assert data["segments"] == [
        {"index": 0, "start": 0, "end": 499, "downloaded": 200, "speed_bps": 60},
        {"index": 1, "start": 500, "end": 999, "downloaded": 100, "speed_bps": 39},
    ]
    assert segment_repo.flushed == [("file", {0: 200, 1: 100})]
    subscription.close()


@pytest.mark.asyncio
async def test_an_unsegmented_flush_omits_the_key_entirely() -> None:
    """Absent, not empty: the UI reads a missing key as 'nothing changed'."""
    hub = EventHub()
    subscription = hub.subscribe()
    repo, segment_repo = FakeTaskRepo(), FakeSegmentRepo()
    worker = _worker(hub, repo, segment_repo)

    await worker._flush(uuid.uuid4(), "file", _sample(()), offset=0, total=1000)

    _, data = await anext(aiter(subscription))
    assert "segments" not in data
    assert segment_repo.flushed == []
    subscription.close()


@pytest.mark.asyncio
async def test_the_offset_of_an_earlier_part_still_shifts_the_totals() -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    worker = _worker(hub)

    await worker._flush(
        uuid.uuid4(),
        "audio",
        _sample((SegmentProgress(0, 0, 999, 300, 99),)),
        offset=2000,
        total=3000,
    )

    _, data = await anext(aiter(subscription))
    assert data["downloaded_bytes"] == 2300
    assert data["progress"] == 76
    subscription.close()
