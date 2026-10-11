"""``TrafficRecorder``: what it adds up from the downloads it reads, tick by tick."""

import uuid
from datetime import UTC, datetime, timedelta

import pytest
from src.data.repo.stat import DownloadSnapshot, TrafficDelta
from src.data.type import DownloadStatus, SourceKind
from src.service.download.live import Live, LiveStats
from src.service.stat import TrafficRecorder
from src.service.stat.recorder import SPEED_RETENTION

from .fake_stat_repo import FakeStatRepo

START = datetime(2026, 10, 11, 9, 0, 5, tzinfo=UTC)
PROVIDER = uuid.uuid4()
DAY = START.date()


class Clock:
    def __init__(self) -> None:
        self.at = START

    def __call__(self) -> datetime:
        return self.at

    def advance(self, seconds: float) -> None:
        self.at += timedelta(seconds=seconds)


def a_download(
    *,
    status: DownloadStatus = DownloadStatus.DOWNLOADING,
    downloaded: int = 0,
    uploaded: int = 0,
    created: datetime | None = None,
    completed: datetime | None = None,
    deleted: datetime | None = None,
    kind: SourceKind = SourceKind.DIRECT,
) -> DownloadSnapshot:
    return DownloadSnapshot(
        id=uuid.uuid4(),
        status=status,
        downloaded_size=downloaded,
        uploaded_size=uploaded,
        created_at=created or START + timedelta(seconds=1),
        completed_at=completed,
        deleted_at=deleted,
        kind=kind,
        provider_id=PROVIDER,
    )


@pytest.fixture
def clock() -> Clock:
    return Clock()


@pytest.fixture
def repo() -> FakeStatRepo:
    return FakeStatRepo()


@pytest.fixture
def live() -> LiveStats:
    return LiveStats()


@pytest.fixture
def recorder(repo: FakeStatRepo, live: LiveStats, clock: Clock) -> TrafficRecorder:
    return TrafficRecorder(repo, live, clock=clock)


def total(repo: FakeStatRepo, kind: SourceKind = SourceKind.DIRECT) -> TrafficDelta:
    return repo.traffic_rows.get((DAY, kind, PROVIDER), TrafficDelta())


@pytest.mark.asyncio
async def test_a_new_download_counts_from_zero_and_adds_only_what_moved(
    recorder: TrafficRecorder, repo: FakeStatRepo, clock: Clock
) -> None:
    row = a_download(downloaded=1000)
    repo.put(row)
    clock.advance(10)
    await recorder.tick()
    assert total(repo).downloaded_size == 1000

    repo.change(row.id, downloaded_size=1800)
    clock.advance(10)
    await recorder.tick()
    assert total(repo).downloaded_size == 1800

    clock.advance(10)
    await recorder.tick()
    assert total(repo).downloaded_size == 1800


@pytest.mark.asyncio
async def test_a_download_from_before_the_recorder_started_counts_only_from_there(
    recorder: TrafficRecorder, repo: FakeStatRepo, clock: Clock
) -> None:
    row = a_download(downloaded=5000, created=START - timedelta(days=2))
    repo.put(row)
    clock.advance(10)
    await recorder.tick()
    assert total(repo) == TrafficDelta()

    repo.change(row.id, downloaded_size=5600)
    clock.advance(10)
    await recorder.tick()
    assert total(repo).downloaded_size == 600


@pytest.mark.asyncio
async def test_finishing_counts_once_and_a_torrent_that_seeds_counts_its_uploads(
    recorder: TrafficRecorder, repo: FakeStatRepo, clock: Clock
) -> None:
    row = a_download(kind=SourceKind.TORRENT, downloaded=100, status=DownloadStatus.DOWNLOADING)
    repo.put(row)
    clock.advance(10)
    await recorder.tick()

    repo.change(row.id, status=DownloadStatus.SEEDING, uploaded_size=40)
    clock.advance(10)
    await recorder.tick()
    repo.change(row.id, uploaded_size=90)
    clock.advance(10)
    await recorder.tick()
    repo.change(row.id, status=DownloadStatus.COMPLETED)
    clock.advance(10)
    await recorder.tick()

    done = total(repo, SourceKind.TORRENT)
    assert (done.downloaded_size, done.uploaded_size, done.finished) == (100, 90, 1)


@pytest.mark.asyncio
async def test_a_failure_counts_each_time_it_happens_and_a_removal_once(
    recorder: TrafficRecorder, repo: FakeStatRepo, clock: Clock
) -> None:
    row = a_download(downloaded=10)
    repo.put(row)
    for status in (
        DownloadStatus.FAILED,
        DownloadStatus.FAILED,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.FAILED,
    ):
        repo.change(row.id, status=status)
        clock.advance(10)
        await recorder.tick()
    assert total(repo).failed == 2

    clock.advance(10)
    repo.change(row.id, status=DownloadStatus.CANCELLED, deleted_at=clock())
    for _ in range(2):
        clock.advance(10)
        await recorder.tick()
    assert (total(repo).failed, total(repo).removed) == (2, 1)


@pytest.mark.asyncio
async def test_a_download_at_rest_is_not_read_again(
    recorder: TrafficRecorder, repo: FakeStatRepo, clock: Clock
) -> None:
    row = a_download(status=DownloadStatus.COMPLETED, downloaded=50)
    repo.put(row)
    clock.advance(10)
    await recorder.tick()
    clock.advance(10)
    await recorder.tick()

    assert repo.watched[1] == set()
    assert (total(repo).downloaded_size, total(repo).finished) == (50, 1)


@pytest.mark.asyncio
async def test_a_failed_write_is_counted_on_the_next_tick(
    recorder: TrafficRecorder, repo: FakeStatRepo, clock: Clock
) -> None:
    repo.put(a_download(downloaded=700))
    repo.fail_next_write = RuntimeError("database away")
    clock.advance(10)
    with pytest.raises(RuntimeError):
        await recorder.tick()
    assert total(repo) == TrafficDelta()

    clock.advance(10)
    await recorder.tick()
    assert total(repo).downloaded_size == 700


@pytest.mark.asyncio
async def test_speed_is_sampled_only_while_something_moves(
    recorder: TrafficRecorder, repo: FakeStatRepo, live: LiveStats, clock: Clock
) -> None:
    hour = START.replace(minute=0, second=0)
    clock.advance(10)
    await recorder.tick()
    assert repo.speed_rows == {}

    first, second = uuid.uuid4(), uuid.uuid4()
    live.set(first, Live(speed_bps=300))
    live.set(second, Live(speed_bps=100))
    await recorder.tick()
    live.set(second, Live(speed_bps=0))
    clock.advance(10)
    await recorder.tick()

    row = repo.speed_rows[hour]
    assert (row.total_bps, row.maximum_bps, row.samples) == (700, 400, 2)


@pytest.mark.asyncio
async def test_speed_older_than_30_days_is_pruned_once_an_hour(
    recorder: TrafficRecorder, repo: FakeStatRepo, clock: Clock
) -> None:
    for _ in range(3):
        clock.advance(10)
        await recorder.tick()
    assert repo.pruned == [START.replace(minute=0, second=0) - SPEED_RETENTION]

    clock.advance(3600)
    await recorder.tick()
    assert len(repo.pruned) == 2


@pytest.mark.asyncio
async def test_what_failed_before_the_recorder_started_is_not_news_but_a_new_failure_is(
    recorder: TrafficRecorder, repo: FakeStatRepo, clock: Clock
) -> None:
    old = a_download(status=DownloadStatus.FAILED, downloaded=5, created=START - timedelta(days=3))
    repo.put(old)
    clock.advance(10)
    await recorder.tick()
    assert total(repo).failed == 0

    # Queued since long ago, claimed and failed between two ticks, never seen moving.
    queued = a_download(status=DownloadStatus.PENDING, created=START - timedelta(days=1))
    repo.put(queued)
    repo.change(queued.id, status=DownloadStatus.FAILED)
    clock.advance(10)
    await recorder.tick()
    assert total(repo).failed == 1


@pytest.mark.asyncio
async def test_removing_a_finished_download_that_was_at_rest_is_counted(
    recorder: TrafficRecorder, repo: FakeStatRepo, clock: Clock
) -> None:
    row = a_download(status=DownloadStatus.COMPLETED, downloaded=9, created=START - timedelta(days=1), completed=START)
    clock.advance(10)
    await recorder.tick()
    assert total(repo) == TrafficDelta()

    clock.advance(10)
    repo.put(row)
    repo.change(row.id, status=DownloadStatus.CANCELLED, deleted_at=clock())
    clock.advance(10)
    await recorder.tick()
    clock.advance(10)
    await recorder.tick()

    assert (total(repo).removed, total(repo).finished, total(repo).downloaded_size) == (1, 0, 0)


@pytest.mark.asyncio
async def test_a_download_that_finished_since_the_last_read_counts_as_finished(
    recorder: TrafficRecorder, repo: FakeStatRepo, clock: Clock
) -> None:
    clock.advance(10)
    await recorder.tick()
    clock.advance(10)
    repo.put(
        a_download(
            status=DownloadStatus.COMPLETED, downloaded=77, created=START - timedelta(hours=1), completed=clock()
        )
    )
    await recorder.tick()

    assert (total(repo).finished, total(repo).downloaded_size) == (1, 0)
