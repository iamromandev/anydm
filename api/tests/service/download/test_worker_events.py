import uuid
from pathlib import Path
from typing import Any, cast

from src.lib.event import EventHub
from src.service.download.control import DownloadControl
from src.service.download.download_worker import DownloadWorker


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
