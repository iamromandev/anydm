"""The worker waits for disk space instead of failing for want of it."""

import errno
import uuid
from collections import namedtuple
from datetime import timedelta
from pathlib import Path
from typing import Any, cast

import pytest
from src.core.common import now
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.event import EventHub
from src.service.download.control import DownloadControl
from src.service.download.disk import DiskGuard
from src.service.download.download_worker import DownloadWorker
from src.service.download.paths import part_path

GIB = 1024**3
_Usage = namedtuple("_Usage", ["total", "used", "free"])


def _disk(free: int, min_free: int = GIB) -> DiskGuard:
    return DiskGuard("/data", min_free, usage=lambda _path: _Usage(100 * GIB, 0, free))


class FakeRow:
    """A claimed direct download, as ``claim_next`` hands it over."""

    def __init__(self) -> None:
        self.id = uuid.uuid4()
        self.filename = "big.iso"
        self.platform = Platform.DIRECT
        self.preset = Preset.BEST
        self.kind = Kind.FILE
        self.source_url = "https://cdn.test/big.iso"
        self.total_bytes: int | None = None
        self.attempts = 0
        self.status = TaskStatus.DOWNLOADING
        self.error: str | None = None
        self.error_code: str | None = None
        self.next_attempt_at: Any = None
        self.speed_bps = 0
        self.eta_seconds: int | None = None
        self.saved: list[list[str]] = []

    async def save(self, update_fields: list[str]) -> None:
        self.saved.append(list(update_fields))

    async def refresh_from_db(self) -> None:
        return None


class FakeSegmentRepo:
    async def progress(self, task_id: uuid.UUID, part: str) -> int:
        return 0


class FakeEngine:
    """Probes a source of ``total`` bytes, then writes a little and optionally fails."""

    def __init__(self, *, total: int | None = None, fail: BaseException | None = None) -> None:
        self.total = total
        self.fail = fail
        self.fetched = 0

    async def fetch(self, source: Any, dest: Path, **kwargs: Any) -> int:
        self.fetched += 1
        await kwargs["on_probe"](self.total)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(b"x" * 10)
        if self.fail is not None:
            raise self.fail
        return 10


def _worker(tmp_path: Path, disk: DiskGuard, engine: FakeEngine, hub: EventHub) -> DownloadWorker:
    stub = cast(Any, object())
    return DownloadWorker(
        name="test",
        repo=stub,
        segment_repo=cast(Any, FakeSegmentRepo()),
        client=stub,
        engine=cast(Any, engine),
        post_processor=stub,
        control=DownloadControl(),
        hub=hub,
        downloads_root=tmp_path,
        max_attempts=3,
        segments=4,
        disk=disk,
    )


def _assert_waiting_for_space(row: FakeRow) -> None:
    assert row.status == TaskStatus.PENDING
    assert row.error_code == "insufficient_storage"
    assert row.error is not None and row.error.startswith("Not enough disk space")
    # The re-check is 30 s out: long enough not to spin, short enough to notice.
    assert timedelta(seconds=25) < row.next_attempt_at - now() <= timedelta(seconds=30)
    # Waiting for space is not a failed try, so the retry budget is untouched.
    assert row.attempts == 0


@pytest.mark.asyncio
async def test_a_task_claimed_on_a_full_disk_waits_instead_of_starting(tmp_path: Path) -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    engine = FakeEngine()
    row = FakeRow()

    await _worker(tmp_path, _disk(free=GIB // 2), engine, hub).run_task(cast(Any, row))

    assert engine.fetched == 0
    _assert_waiting_for_space(row)
    event, data = await anext(aiter(subscription))
    assert (event, data["status"]) == ("task", "pending")
    subscription.close()


@pytest.mark.asyncio
async def test_a_source_too_big_for_the_space_left_waits_once_probed(tmp_path: Path) -> None:
    # 3 GiB free clears the 1 GiB minimum, but not a 5 GiB file on top of it.
    engine = FakeEngine(total=5 * GIB)
    row = FakeRow()

    await _worker(tmp_path, _disk(free=3 * GIB), engine, EventHub()).run_task(cast(Any, row))

    assert engine.fetched == 1
    _assert_waiting_for_space(row)


@pytest.mark.asyncio
async def test_a_disk_that_fills_mid_write_waits_and_keeps_the_part(tmp_path: Path) -> None:
    engine = FakeEngine(total=GIB, fail=OSError(errno.ENOSPC, "No space left on device"))
    row = FakeRow()

    await _worker(tmp_path, _disk(free=10 * GIB), engine, EventHub()).run_task(cast(Any, row))

    _assert_waiting_for_space(row)
    # The bytes written so far are what the next attempt resumes from.
    assert part_path(tmp_path, row.id, "file").stat().st_size == 10


@pytest.mark.asyncio
async def test_other_write_errors_still_fail_the_task(tmp_path: Path) -> None:
    engine = FakeEngine(total=GIB, fail=OSError(errno.EACCES, "Permission denied"))
    row = FakeRow()

    await _worker(tmp_path, _disk(free=10 * GIB), engine, EventHub()).run_task(cast(Any, row))

    assert row.status == TaskStatus.FAILED
    assert row.error_code == "server_error"
    assert row.attempts == 1
