"""Each run of a download is one try through one mirror, closed with how it ended."""

from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.data.type import AttemptStatus, DownloadStatus, MirrorStatus
from src.service.download.downloader import Stopped

from tests.service.download.memory import MemoryFiles, RecordingHub
from tests.service.download.workers import FakeAttempts, FlushRecordingRepo, direct_row, worker


class FailingEngine:
    def __init__(self, fail: BaseException) -> None:
        self.fail = fail
        self.fetched = 0

    async def fetch(self, source: Any, dest: Path, **kwargs: Any) -> int:
        self.fetched += 1
        raise self.fail


def _refused() -> Error:
    return Error.create(
        code=Code.NOT_FOUND, message="Upstream returned status 404", error_type=ErrorType.DOES_NOT_EXIST
    )


@pytest.mark.asyncio
async def test_a_download_with_every_mirror_spent_fails_without_fetching(tmp_path: Path) -> None:
    files = MemoryFiles()
    row = await direct_row(files)
    row.mirrors[0].status = MirrorStatus.FAILED
    engine = FailingEngine(_refused())

    await worker(tmp_path, files=files, engine=engine, hub=RecordingHub()).run_task(row)

    assert (row.status, engine.fetched) == (DownloadStatus.FAILED, 0)
    assert row.error == "No source left to try"


@pytest.mark.asyncio
async def test_a_stop_closes_the_try_as_cancelled(tmp_path: Path) -> None:
    files, attempts = MemoryFiles(), FakeAttempts()
    row = await direct_row(files, status=DownloadStatus.PAUSED)

    await worker(
        tmp_path, files=files, attempts=attempts, engine=FailingEngine(Stopped()), hub=RecordingHub()
    ).run_task(row)

    assert attempts.closed == [AttemptStatus.CANCELLED]
    assert attempts.retired == []


@pytest.mark.asyncio
async def test_a_refusal_with_a_spare_mirror_goes_back_to_the_queue_with_a_fresh_budget(tmp_path: Path) -> None:
    files, attempts = MemoryFiles(), FakeAttempts(spare_mirror=True)
    row = await direct_row(files)

    await worker(
        tmp_path, files=files, attempts=attempts, engine=FailingEngine(_refused()), hub=RecordingHub()
    ).run_task(row)

    assert (attempts.closed, attempts.retired) == ([AttemptStatus.FAILED], [MirrorStatus.FAILED])
    assert (row.status, row.attempts, row.next_attempt_at) == (DownloadStatus.PENDING, 0, None)


@pytest.mark.asyncio
async def test_a_refusal_on_the_last_mirror_fails_for_good(tmp_path: Path) -> None:
    files, attempts = MemoryFiles(), FakeAttempts()
    row = await direct_row(files)

    await worker(
        tmp_path, files=files, attempts=attempts, engine=FailingEngine(_refused()), hub=RecordingHub()
    ).run_task(row)

    assert (attempts.closed, attempts.retired) == ([AttemptStatus.FAILED], [MirrorStatus.FAILED])
    assert row.status == DownloadStatus.FAILED


# A pause or remove made while the try ran stands: the try's outcome is not written over it.


@pytest.mark.asyncio
async def test_a_download_paused_while_its_try_fails_stays_paused(tmp_path: Path) -> None:
    files, attempts, hub = MemoryFiles(), FakeAttempts(), RecordingHub()
    # The row as the database now holds it: the person paused it mid-try.
    row = await direct_row(files, status=DownloadStatus.PAUSED)
    repo = FlushRecordingRepo(person_got_there_first=True)

    await worker(
        tmp_path, files=files, repo=repo, attempts=attempts, engine=FailingEngine(_refused()), hub=hub
    ).run_task(row)

    assert (row.status, row.next_attempt_at, row.error) == (DownloadStatus.PAUSED, None, None)
    # The person ended the try, not the source: no failure on the mirror, no retry spent.
    assert (attempts.closed, attempts.retired, row.attempts) == ([AttemptStatus.CANCELLED], [], 0)
    assert hub.named("download")[-1]["status"] == "paused"


@pytest.mark.asyncio
async def test_a_paused_download_is_not_failed_over_to_its_next_mirror(tmp_path: Path) -> None:
    files, attempts = MemoryFiles(), FakeAttempts(spare_mirror=True)
    row = await direct_row(files, status=DownloadStatus.PAUSED)

    await worker(
        tmp_path,
        files=files,
        repo=FlushRecordingRepo(person_got_there_first=True),
        attempts=attempts,
        engine=FailingEngine(_refused()),
        hub=RecordingHub(),
    ).run_task(row)

    assert (row.status, attempts.retired) == (DownloadStatus.PAUSED, [])


@pytest.mark.asyncio
async def test_a_failure_still_in_the_worker_s_hands_is_written(tmp_path: Path) -> None:
    files, repo = MemoryFiles(), FlushRecordingRepo()
    row = await direct_row(files)

    await worker(tmp_path, files=files, repo=repo, engine=FailingEngine(_refused()), hub=RecordingHub()).run_task(row)

    (ended,) = repo.ended
    assert (ended["status"], ended["error_code"]) == (DownloadStatus.FAILED, "does_not_exist")
