"""``AttemptDatabaseRepo`` against Postgres: why a try failed is on the try, and a download reads its newest."""

import uuid

import pytest
from src.core.common import now
from src.data.db.model import Attempt, Download, Mirror
from src.data.repo import AttemptDatabaseRepo
from src.data.repo.download.interface import Opened
from src.data.repo.download.interface.download import RELATED
from src.data.type import AttemptStatus

from tests.integration.rows import a_download, a_torrent_download

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]


async def _open(repo: AttemptDatabaseRepo, address: str = "https://e.com/a.bin") -> tuple[Opened, Download]:
    row = await a_download(address)
    loaded = await Download.get(id=row.id).prefetch_related(*RELATED)
    opened = await repo.open(loaded)
    assert opened is not None
    return opened, row


@pytest.mark.asyncio
async def test_a_try_that_fails_keeps_why_and_one_that_completes_keeps_none() -> None:
    repo = AttemptDatabaseRepo()
    opened, _ = await _open(repo)

    await repo.close(opened, AttemptStatus.FAILED, error="Upstream returned status 404", error_code="does_not_exist")
    failed = await Attempt.get(id=opened.attempt_id)
    assert (failed.status, failed.error, failed.error_code) == (
        AttemptStatus.FAILED,
        "Upstream returned status 404",
        "does_not_exist",
    )

    opened, _ = await _open(repo, "https://e.com/b.bin")
    await repo.close(opened, AttemptStatus.COMPLETED)
    done = await Attempt.get(id=opened.attempt_id)
    assert (done.status, done.error, done.error_code) == (AttemptStatus.COMPLETED, None, None)


@pytest.mark.asyncio
async def test_a_failure_with_no_try_is_recorded_on_the_primary_mirror() -> None:
    repo = AttemptDatabaseRepo()
    row = await a_torrent_download("d" * 40, name="Release")

    await repo.record_failure(row.id, "no space left on device", None)

    (attempt,) = await Attempt.filter(mirror__download_id=row.id)
    assert (attempt.status, attempt.error, attempt.error_code, attempt.completed_at is not None) == (
        AttemptStatus.FAILED,
        "no space left on device",
        None,
        True,
    )
    mirror = await Mirror.get(download_id=row.id)
    assert attempt.mirror.id == mirror.id


@pytest.mark.asyncio
async def test_a_download_with_no_mirror_records_no_failure() -> None:
    await AttemptDatabaseRepo().record_failure(uuid.uuid4(), "nothing to attach to", None)

    assert await Attempt.all().count() == 0


@pytest.mark.asyncio
async def test_the_newest_try_speaks_for_a_download_and_a_download_with_no_try_is_left_out() -> None:
    repo = AttemptDatabaseRepo()
    first, row = await _open(repo)
    await repo.close(first, AttemptStatus.FAILED, error="first", error_code="a")
    # A later try, as the retry after it would be: this one has not failed.
    mirror = await Mirror.get(download_id=row.id)
    await Attempt.create(mirror_id=mirror.id, status=AttemptStatus.RUNNING, started_at=now())
    untried = await a_download("https://e.com/c.bin")
    other, other_row = await _open(repo, "https://e.com/d.bin")
    await repo.close(other, AttemptStatus.FAILED, error="other", error_code="b")

    found = await repo.latest_errors([row.id, untried.id, other_row.id])

    assert found == {row.id: (None, None), other_row.id: ("other", "b")}
    assert await repo.latest_errors([]) == {}
