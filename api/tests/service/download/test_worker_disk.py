"""The worker waits for disk space instead of failing for want of it."""

import errno
from collections import namedtuple
from datetime import timedelta
from pathlib import Path
from typing import Any

import pytest
from src.core.common import now
from src.data.type import AttemptStatus, DownloadStatus
from src.service.download.disk import DiskGuard
from src.service.download.paths import part_path

from tests.service.download.memory import MemoryFiles, RecordingHub
from tests.service.download.workers import FakeAttempts, FlushRecordingRepo, direct_row, worker

GIB = 1024**3
_Usage = namedtuple("_Usage", ["total", "used", "free"])


def _disk(free: int, min_free: int = GIB) -> DiskGuard:
    return DiskGuard("/data", min_free, usage=lambda _path: _Usage(100 * GIB, 0, free))


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


class FinishingPostProcessor:
    """Turns the part into the finished file, as the real one does for a plain download."""

    async def run(self, download: Any, parts: dict[str, Path], destination: Path, **_: Any) -> None:
        parts["file"].replace(destination)


def _assert_waiting_for_space(row: Any) -> None:
    assert row.status == DownloadStatus.PENDING
    assert row.error_code == "insufficient_storage"
    assert row.error is not None and row.error.startswith("Not enough disk space")
    # The re-check is 30 s out: long enough not to spin, short enough to notice.
    assert timedelta(seconds=25) < row.next_attempt_at - now() <= timedelta(seconds=30)
    # Waiting for space is not a failed try, so the retry budget is untouched.
    assert row.attempts == 0


@pytest.mark.asyncio
async def test_a_download_claimed_on_a_full_disk_waits_instead_of_starting(tmp_path: Path) -> None:
    files, hub, engine = MemoryFiles(), RecordingHub(), FakeEngine()
    row = await direct_row(files)

    await worker(tmp_path, files=files, disk=_disk(free=GIB // 2), engine=engine, hub=hub).run_task(row)

    assert engine.fetched == 0
    _assert_waiting_for_space(row)
    assert hub.named("download")[0]["status"] == "pending"


@pytest.mark.asyncio
async def test_a_source_too_big_for_the_space_left_waits_once_probed(tmp_path: Path) -> None:
    # 3 GiB free clears the 1 GiB minimum, but not a 5 GiB file on top of it.
    files, engine = MemoryFiles(), FakeEngine(total=5 * GIB)
    row = await direct_row(files)

    await worker(tmp_path, files=files, disk=_disk(free=3 * GIB), engine=engine, hub=RecordingHub()).run_task(row)

    assert engine.fetched == 1
    _assert_waiting_for_space(row)


@pytest.mark.asyncio
async def test_a_disk_that_fills_mid_write_waits_and_keeps_the_part(tmp_path: Path) -> None:
    files = MemoryFiles()
    engine = FakeEngine(total=GIB, fail=OSError(errno.ENOSPC, "No space left on device"))
    row = await direct_row(files)

    await worker(tmp_path, files=files, disk=_disk(free=10 * GIB), engine=engine, hub=RecordingHub()).run_task(row)

    _assert_waiting_for_space(row)
    # The bytes written so far are what the next attempt resumes from.
    assert part_path(tmp_path, row.id, "file").stat().st_size == 10


@pytest.mark.asyncio
async def test_other_write_errors_still_fail_the_download(tmp_path: Path) -> None:
    files = MemoryFiles()
    engine = FakeEngine(total=GIB, fail=OSError(errno.EACCES, "Permission denied"))
    row = await direct_row(files)

    await worker(tmp_path, files=files, disk=_disk(free=10 * GIB), engine=engine, hub=RecordingHub()).run_task(row)

    assert row.status == DownloadStatus.FAILED
    assert row.error_code == "server_error"
    assert row.attempts == 1


@pytest.mark.asyncio
async def test_a_download_paused_as_the_disk_fills_stays_paused_and_keeps_the_part(tmp_path: Path) -> None:
    files = MemoryFiles()
    engine = FakeEngine(total=GIB, fail=OSError(errno.ENOSPC, "No space left on device"))
    row = await direct_row(files, status=DownloadStatus.PAUSED)
    repo = FlushRecordingRepo(person_got_there_first=True)

    await worker(
        tmp_path, files=files, repo=repo, disk=_disk(free=10 * GIB), engine=engine, hub=RecordingHub()
    ).run_task(row)

    assert (row.status, row.next_attempt_at, row.error, row.attempts) == (DownloadStatus.PAUSED, None, None, 0)
    assert part_path(tmp_path, row.id, "file").stat().st_size == 10


@pytest.mark.asyncio
async def test_a_download_removed_as_it_finishes_leaves_no_file_behind(tmp_path: Path) -> None:
    files = MemoryFiles()
    row = await direct_row(files, status=DownloadStatus.CANCELLED, deleted_at=now())
    repo, attempts = FlushRecordingRepo(person_got_there_first=True), FakeAttempts()

    await worker(
        tmp_path,
        files=files,
        repo=repo,
        attempts=attempts,
        engine=FakeEngine(total=10),
        post=FinishingPostProcessor(),
        hub=RecordingHub(),
    ).run_task(row)

    # Refused at completion, not on the way to a failure.
    assert (row.status, attempts.closed) == (DownloadStatus.CANCELLED, [AttemptStatus.CANCELLED])
    assert {DownloadStatus.PENDING, DownloadStatus.PAUSED} <= repo.over[-1]
    assert [path for path in tmp_path.rglob("*") if path.is_file()] == []


@pytest.mark.asyncio
async def test_a_finished_file_is_recorded_over_a_pause_and_resume_but_not_over_a_remove(tmp_path: Path) -> None:
    files, repo = MemoryFiles(), FlushRecordingRepo()
    row = await direct_row(files)

    await worker(
        tmp_path, files=files, repo=repo, engine=FakeEngine(total=10), post=FinishingPostProcessor(), hub=RecordingHub()
    ).run_task(row)

    (over,) = repo.over
    assert {DownloadStatus.PENDING, DownloadStatus.PAUSED, DownloadStatus.DOWNLOADING} <= over
    assert DownloadStatus.CANCELLED not in over
    assert row.status == DownloadStatus.COMPLETED
