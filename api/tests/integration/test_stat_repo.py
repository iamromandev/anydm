"""``StatDatabaseRepo`` against Postgres: the rollup upserts, the range reads, retention, and what the recorder reads."""

from datetime import UTC, date, datetime, timedelta

import pytest
from src.core.common import now
from src.data.db.model import Provider, Source, Speed, Traffic
from src.data.repo.stat import StatDatabaseRepo, TrafficDelta
from src.data.type import DownloadStatus, SourceKind

from tests.integration.rows import a_collection, a_download, a_source

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]

DAY = date(2026, 10, 11)
HOUR = datetime(2026, 10, 11, 9, tzinfo=UTC)


async def a_provider(name: str = "Youtube", kind: SourceKind = SourceKind.SITE) -> Provider:
    source = await a_source(name, f"https://{name.lower()}.com/watch?v=1", kind)
    return await Provider.get(id=source.provider_id)


@pytest.mark.asyncio
async def test_traffic_adds_up_in_one_row_per_day_kind_and_provider() -> None:
    repo, provider = StatDatabaseRepo(), await a_provider()

    await repo.add_traffic(DAY, SourceKind.SITE, provider.id, TrafficDelta(100, 5, 1, 0, 0))
    await repo.add_traffic(DAY, SourceKind.SITE, provider.id, TrafficDelta(50, 0, 0, 2, 1))
    await repo.add_traffic(DAY + timedelta(days=1), SourceKind.SITE, provider.id, TrafficDelta(7))

    assert await Traffic.all().count() == 2
    rows = await repo.traffic(DAY, DAY + timedelta(days=1))
    assert [(row.day, row.provider_slug, row.provider_name) for row in rows] == [
        (DAY, "youtube", "Youtube"),
        (DAY + timedelta(days=1), "youtube", "Youtube"),
    ]
    assert rows[0].delta == TrafficDelta(150, 5, 1, 2, 1)
    assert await repo.traffic(DAY + timedelta(days=2), DAY + timedelta(days=3)) == []


@pytest.mark.asyncio
async def test_traffic_outlives_its_provider() -> None:
    repo, provider = StatDatabaseRepo(), await a_provider()
    await repo.add_traffic(DAY, SourceKind.SITE, provider.id, TrafficDelta(100))

    await Source.filter(provider_id=provider.id).delete()
    await Provider.filter(id=provider.id).delete()

    (row,) = await repo.traffic(DAY, DAY)
    assert (row.provider_slug, row.provider_name, row.delta.downloaded_size) == (None, None, 100)


@pytest.mark.asyncio
async def test_speed_keeps_the_sum_the_count_and_the_highest_reading() -> None:
    repo = StatDatabaseRepo()
    for bps in (300, 900, 100):
        await repo.add_speed(HOUR, bps)
    await repo.add_speed(HOUR + timedelta(hours=1), 50)

    assert await Speed.all().count() == 2
    first, second = await repo.speeds(HOUR, HOUR + timedelta(hours=2))
    assert (first.total_bps, first.maximum_bps, first.samples) == (1300, 900, 3)
    assert (second.hour, second.samples) == (HOUR + timedelta(hours=1), 1)
    assert [row.hour for row in await repo.speeds(HOUR, HOUR + timedelta(hours=1))] == [HOUR]


@pytest.mark.asyncio
async def test_pruning_drops_only_the_hours_before_the_cutoff() -> None:
    repo = StatDatabaseRepo()
    for days in (40, 31, 29, 0):
        await repo.add_speed(HOUR - timedelta(days=days), 10)

    gone = await repo.prune_speed(HOUR - timedelta(days=30))

    assert gone == 2
    assert [row.hour for row in await repo.speeds(HOUR - timedelta(days=100), HOUR + timedelta(days=1))] == [
        HOUR - timedelta(days=29),
        HOUR,
    ]


@pytest.mark.asyncio
async def test_snapshots_read_what_moves_what_failed_what_is_watched_and_what_is_new() -> None:
    repo, later = StatDatabaseRepo(), now() + timedelta(hours=1)
    moving = await a_download("https://example.com/1.bin", status=DownloadStatus.DOWNLOADING, downloaded_size=10)
    seeding = await a_download("https://example.com/2.bin", status=DownloadStatus.SEEDING, uploaded_size=3)
    failed = await a_download("https://example.com/3.bin", status=DownloadStatus.FAILED)
    watched = await a_download("https://example.com/4.bin", status=DownloadStatus.PAUSED)
    done_since = await a_download(
        "https://example.com/5.bin", status=DownloadStatus.COMPLETED, completed_at=later + timedelta(hours=1)
    )
    removed_since = await a_download(
        "https://example.com/6.bin", status=DownloadStatus.CANCELLED, deleted_at=later + timedelta(hours=1)
    )
    await a_download("https://example.com/7.bin", status=DownloadStatus.PAUSED)
    await a_download("https://example.com/8.bin", status=DownloadStatus.COMPLETED)
    await a_collection(status=DownloadStatus.DOWNLOADING)

    found = {row.id: row for row in await repo.snapshots({watched.id}, later)}

    assert set(found) == {moving.id, seeding.id, failed.id, watched.id, done_since.id, removed_since.id}
    assert (found[moving.id].downloaded_size, found[seeding.id].uploaded_size) == (10, 3)
    assert (found[moving.id].kind, found[moving.id].provider_id) == (
        SourceKind.DIRECT,
        (await a_source("http", "https://example.com/1.bin", SourceKind.DIRECT)).provider_id,
    )
    assert found[removed_since.id].removed and not found[moving.id].removed
    assert found[done_since.id].completed_at is not None


@pytest.mark.asyncio
async def test_snapshots_include_what_was_created_since() -> None:
    repo = StatDatabaseRepo()
    fresh = await a_download("https://example.com/new.bin", status=DownloadStatus.PAUSED)

    assert [row.id for row in await repo.snapshots(set(), now() - timedelta(hours=1))] == [fresh.id]
    assert await repo.snapshots(set(), now() + timedelta(hours=1)) == []
