"""``StatService``: the range, the grouping, and the average."""

import uuid
from datetime import UTC, date, datetime

import pytest
from src.core.error import Error
from src.data.repo.stat import SpeedRow, TrafficDelta
from src.data.type import SourceKind, StatGroup
from src.service.stat import StatService

from .fake_stat_repo import FakeStatRepo

NOW = datetime(2026, 10, 11, 12, 0, tzinfo=UTC)
YOUTUBE, HTTP = uuid.uuid4(), uuid.uuid4()


@pytest.fixture
def repo() -> FakeStatRepo:
    repo = FakeStatRepo()
    repo.providers = {YOUTUBE: ("youtube", "Youtube"), HTTP: ("http", "http")}
    repo.traffic_rows = {
        (date(2026, 10, 10), SourceKind.SITE, YOUTUBE): TrafficDelta(100, 0, 1, 0, 0),
        (date(2026, 10, 10), SourceKind.DIRECT, HTTP): TrafficDelta(50, 0, 1, 1, 0),
        (date(2026, 10, 11), SourceKind.SITE, YOUTUBE): TrafficDelta(30, 0, 0, 0, 2),
        (date(2026, 9, 1), SourceKind.SITE, YOUTUBE): TrafficDelta(999, 0, 9, 0, 0),
    }
    return repo


@pytest.fixture
def service(repo: FakeStatRepo) -> StatService:
    return StatService(repo, clock=lambda: NOW)


@pytest.mark.asyncio
async def test_by_day_over_the_last_30_days_by_default(service: StatService) -> None:
    stats = await service.stats(None, None, StatGroup.DAY)

    assert (stats.start, stats.end) == (date(2026, 9, 12), date(2026, 10, 11))
    assert [(g.key, g.downloaded_size) for g in stats.groups] == [("2026-10-10", 150), ("2026-10-11", 30)]


@pytest.mark.asyncio
async def test_by_kind_adds_the_days_together(service: StatService) -> None:
    stats = await service.stats(date(2026, 10, 1), date(2026, 10, 11), StatGroup.KIND)

    by_key = {g.key: g for g in stats.groups}
    assert set(by_key) == {"direct", "site"}
    assert (by_key["site"].downloaded_size, by_key["site"].finished, by_key["site"].removed) == (130, 1, 2)
    assert (by_key["direct"].failed, by_key["direct"].name) == (1, None)


@pytest.mark.asyncio
async def test_by_provider_names_the_provider(service: StatService) -> None:
    stats = await service.stats(date(2026, 10, 1), date(2026, 10, 11), StatGroup.PROVIDER)

    assert [(g.key, g.name, g.downloaded_size) for g in stats.groups] == [
        ("http", "http", 50),
        ("youtube", "Youtube", 130),
    ]


@pytest.mark.asyncio
async def test_traffic_of_a_deleted_provider_is_grouped_as_unknown(service: StatService, repo: FakeStatRepo) -> None:
    repo.providers.clear()

    stats = await service.stats(date(2026, 10, 1), date(2026, 10, 11), StatGroup.PROVIDER)

    assert [(g.key, g.name, g.downloaded_size) for g in stats.groups] == [("unknown", None, 180)]


@pytest.mark.asyncio
async def test_speed_points_carry_the_average(service: StatService, repo: FakeStatRepo) -> None:
    hour = datetime(2026, 10, 11, 9, tzinfo=UTC)
    repo.speed_rows = {
        hour: SpeedRow(hour, 700, 400, 2),
        datetime(2026, 8, 1, tzinfo=UTC): SpeedRow(datetime(2026, 8, 1, tzinfo=UTC), 10, 10, 1),
    }

    stats = await service.stats(None, None, StatGroup.DAY)

    assert [(p.hour, p.average_bps, p.maximum_bps) for p in stats.speeds] == [(hour, 350, 400)]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("start", "end"),
    [(date(2026, 10, 11), date(2026, 10, 10)), (date(2025, 1, 1), date(2026, 10, 11))],
)
async def test_a_reversed_or_too_wide_range_is_a_bad_request(service: StatService, start: date, end: date) -> None:
    with pytest.raises(Error) as raised:
        await service.stats(start, end, StatGroup.DAY)

    assert raised.value.code == 400
