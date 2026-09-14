import pytest
from src.core.type import Status
from src.service.health.health_service import HealthService


@pytest.mark.asyncio
async def test_check_health_reports_the_request_address(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("src.service.health.health_service.get_db_health", _true)
    monkeypatch.setattr("src.service.health.health_service.get_db_version", _version)

    health = await HealthService().check_health(host="127.0.0.1", port=9999)

    assert health.host == "127.0.0.1"
    assert health.port == 9999
    assert health.db is not None
    assert health.db.status == Status.SUCCESS
    assert health.db.version == "PostgreSQL 17"


@pytest.mark.asyncio
async def test_check_health_marks_db_error_when_unreachable(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("src.service.health.health_service.get_db_health", _false)
    monkeypatch.setattr("src.service.health.health_service.get_db_version", _none)

    health = await HealthService().check_health(host="127.0.0.1", port=9999)

    assert health.db is not None
    assert health.db.status == Status.ERROR


async def _true() -> bool:
    return True


async def _false() -> bool:
    return False


async def _version() -> str:
    return "PostgreSQL 17"


async def _none() -> None:
    return None
