"""``GET /system/disk``, through the real app's routing and nothing else."""

from collections import namedtuple
from collections.abc import AsyncIterator, Iterator

import httpx
import pytest
import pytest_asyncio
from pydantic import SecretStr
from src.config import get_settings
from src.main import app
from src.service import get_disk_guard
from src.service.download.disk import DiskGuard

GIB = 1024**3
_Usage = namedtuple("_Usage", ["total", "used", "free"])


@pytest.fixture(autouse=True)
def _open_api(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    # A developer's own .env may set a key; these tests are about the route.
    monkeypatch.setattr(get_settings(), "api_key", None)
    yield
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def client() -> AsyncIterator[httpx.AsyncClient]:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://api.test") as http:
        yield http


@pytest.mark.asyncio
async def test_disk_reports_the_download_dir(client: httpx.AsyncClient) -> None:
    app.dependency_overrides[get_disk_guard] = lambda: DiskGuard(
        "/data", GIB, usage=lambda _path: _Usage(100 * GIB, 60 * GIB, 40 * GIB)
    )

    response = await client.get("/system/disk")

    assert response.status_code == 200
    assert response.json()["data"] == {
        "path": "/data",
        "total_bytes": 100 * GIB,
        "free_bytes": 40 * GIB,
        "min_free_bytes": GIB,
    }


@pytest.mark.asyncio
async def test_an_unreadable_disk_is_a_503(client: httpx.AsyncClient) -> None:
    def unreadable(_path: str) -> _Usage:
        raise FileNotFoundError("/data")

    app.dependency_overrides[get_disk_guard] = lambda: DiskGuard("/data", GIB, usage=unreadable)

    assert (await client.get("/system/disk")).status_code == 503


@pytest.mark.asyncio
async def test_disk_is_behind_the_api_key(
    client: httpx.AsyncClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(get_settings(), "api_key", SecretStr("k"))

    assert (await client.get("/system/disk")).status_code == 401
