"""The torrent routes through the real app, with a stub service behind them.

Like ``test_download_routes.py``: ``ASGITransport`` skips the lifespan, so no Postgres.
"""

import uuid
from collections.abc import AsyncIterator, Iterator
from typing import Any

import httpx
import pytest
import pytest_asyncio
from src.config import get_settings
from src.data.schema.torrent import PiecesMapSchema
from src.main import app
from src.service import get_torrent_service


class StubTorrents:
    def __init__(self) -> None:
        self.maps: list[Any] = []

    async def pieces_map(self, download_id: uuid.UUID) -> PiecesMapSchema:
        self.maps.append(download_id)
        return PiecesMapSchema(piece_size=512, count=3, done="oA==", downloading=[])


@pytest.fixture(autouse=True)
def _no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", None)


@pytest.fixture
def torrents() -> Iterator[StubTorrents]:
    stub = StubTorrents()
    app.dependency_overrides[get_torrent_service] = lambda: stub
    yield stub
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def http() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as client:
        yield client


@pytest.mark.asyncio
async def test_pieces_map_is_compact(http: httpx.AsyncClient, torrents: StubTorrents) -> None:
    download_id = uuid.uuid4()

    response = await http.get(f"/download/{download_id}/pieces")

    assert response.status_code == 200
    assert response.json()["data"] == {"piece_size": 512, "count": 3, "done": "oA==", "downloading": []}
    assert torrents.maps == [download_id]
