"""``GET /stats`` through the real app, with an in-memory repository behind it."""

import uuid
from collections.abc import AsyncIterator, Iterator
from datetime import UTC, date, datetime

import httpx
import pytest
import pytest_asyncio
from src.config import get_settings
from src.data.repo.stat import TrafficDelta
from src.data.type import SourceKind
from src.main import app
from src.service import get_stat_service
from src.service.stat import StatService

from ..service.stat.fake_stat_repo import FakeStatRepo

PROVIDER = uuid.uuid4()


@pytest.fixture(autouse=True)
def _no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", None)


@pytest.fixture
def repo() -> Iterator[FakeStatRepo]:
    repo = FakeStatRepo()
    repo.providers = {PROVIDER: ("youtube", "Youtube")}
    repo.traffic_rows = {(date(2026, 10, 10), SourceKind.SITE, PROVIDER): TrafficDelta(100, 5, 1, 0, 0)}
    app.dependency_overrides[get_stat_service] = lambda: StatService(
        repo, clock=lambda: datetime(2026, 10, 11, tzinfo=UTC)
    )
    yield repo
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def http() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as client:
        yield client


@pytest.mark.asyncio
async def test_stats_by_provider_over_a_range(http: httpx.AsyncClient, repo: FakeStatRepo) -> None:
    response = await http.get("/stats", params={"from": "2026-10-01", "to": "2026-10-11", "group": "provider"})

    assert response.status_code == 200
    data = response.json()["data"]
    assert (data["group"], data["start"], data["end"]) == ("provider", "2026-10-01", "2026-10-11")
    assert data["groups"] == [
        {
            "key": "youtube",
            "name": "Youtube",
            "downloaded_size": 100,
            "uploaded_size": 5,
            "finished": 1,
            "failed": 0,
            "removed": 0,
        }
    ]
    assert data["speeds"] == []


@pytest.mark.asyncio
async def test_stats_defaults_to_days_over_the_last_30(http: httpx.AsyncClient, repo: FakeStatRepo) -> None:
    data = (await http.get("/stats")).json()["data"]

    assert (data["group"], data["start"], data["end"]) == ("day", "2026-09-12", "2026-10-11")
    assert [g["key"] for g in data["groups"]] == ["2026-10-10"]


@pytest.mark.asyncio
async def test_stats_refuses_what_it_cannot_answer(http: httpx.AsyncClient, repo: FakeStatRepo) -> None:
    assert (await http.get("/stats", params={"group": "host"})).status_code == 422
    assert (await http.get("/stats", params={"from": "2026-10-11", "to": "2026-10-01"})).status_code == 400
