"""``GET /health/check``, through the real app's routing and nothing else."""

from collections.abc import AsyncIterator

import httpx
import pytest
import pytest_asyncio
from src.core.type import Status
from src.main import app


@pytest_asyncio.fixture
async def client() -> AsyncIterator[httpx.AsyncClient]:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://api.test") as http:
        yield http


@pytest.mark.asyncio
async def test_health_check_reports_db_status(client: httpx.AsyncClient, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("src.service.health.health_service.get_db_health", _true)
    monkeypatch.setattr("src.service.health.health_service.get_db_version", _version)

    response = await client.get("/health/check")

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["db"]["status"] == Status.SUCCESS
    assert data["db"]["version"] == "PostgreSQL 17"


@pytest.mark.asyncio
async def test_health_check_marks_db_error_when_unreachable(
    client: httpx.AsyncClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("src.service.health.health_service.get_db_health", _false)
    monkeypatch.setattr("src.service.health.health_service.get_db_version", _none)

    response = await client.get("/health/check")

    assert response.status_code == 200
    assert response.json()["data"]["db"]["status"] == Status.ERROR


async def _true() -> bool:
    return True


async def _false() -> bool:
    return False


async def _version() -> str:
    return "PostgreSQL 17"


async def _none() -> None:
    return None
