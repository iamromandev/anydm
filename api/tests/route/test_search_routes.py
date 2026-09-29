"""The search routes through the real app, with fake indexers behind SearchService."""

from collections.abc import AsyncIterator, Iterator
from pathlib import Path

import httpx
import pytest
import pytest_asyncio
from src.config import get_settings
from src.lib.sources.torznab_source import TorznabSource
from src.lib.torznab.torznab import Indexer
from src.main import app
from src.service import get_search_service
from src.service.search.search_service import SearchService

FIXTURES = Path(__file__).parents[1] / "fixtures" / "torznab"


@pytest.fixture(autouse=True)
def _no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", None)


def _indexers(request: httpx.Request) -> httpx.Response:
    if request.url.path.endswith("/dl"):
        return httpx.Response(200, content=b"d8:announce3:urle")
    return httpx.Response(200, content=(FIXTURES / "prowlarr.xml").read_bytes())


@pytest.fixture
def indexers() -> Iterator[None]:
    client = httpx.AsyncClient(transport=httpx.MockTransport(_indexers))
    service = SearchService([TorznabSource(Indexer("prowlarr-1", "http://prowlarr:9696/1/api", "abc"))], client, timeout_s=1, limit=100)
    app.dependency_overrides[get_search_service] = lambda: service
    yield
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def http() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as client:
        yield client


@pytest.mark.asyncio
async def test_sources_say_search_is_on(http: httpx.AsyncClient, indexers: None) -> None:
    body = (await http.get("/search/sources")).json()

    assert body["data"] == {"enabled": True, "indexers": ["prowlarr-1"]}


@pytest.mark.asyncio
async def test_a_search_answers_results_and_how_long_it_took(http: httpx.AsyncClient, indexers: None) -> None:
    response = await http.get("/search", params={"q": " bunny ", "category": "movies"})
    data = response.json()["data"]

    assert response.status_code == 200
    assert data["results"][0]["title"] == "Big Buck Bunny 1080p"
    assert data["results"][0]["indexers"] == ["prowlarr-1"]
    assert data["errors"] == []
    assert data["asked"] == ["prowlarr-1"]
    assert isinstance(data["took_ms"], int)


@pytest.mark.asyncio
@pytest.mark.parametrize("params", [{"q": "a"}, {"q": " a "}, {"q": "x" * 201}, {"q": "bunny", "category": "anime"}])
async def test_a_bad_query_is_422(http: httpx.AsyncClient, indexers: None, params: dict[str, str]) -> None:
    assert (await http.get("/search", params=params)).status_code == 422


@pytest.mark.asyncio
@pytest.mark.parametrize("params", [{}, {"q": ""}, {"q": "   "}, {"category": "tv"}, {"q": "", "fresh": "1"}])
async def test_no_query_is_a_browse(http: httpx.AsyncClient, indexers: None, params: dict[str, str]) -> None:
    response = await http.get("/search", params=params)

    assert response.status_code == 200
    assert response.json()["data"]["results"][0]["title"] == "Big Buck Bunny 1080p"


@pytest.mark.asyncio
async def test_a_torrent_is_fetched_from_its_indexer(http: httpx.AsyncClient, indexers: None) -> None:
    ok = await http.post("/search/torrent", json={"link": "http://prowlarr:9696/1/dl"})
    refused = await http.post("/search/torrent", json={"link": "http://evil.test/dl"})

    assert ok.status_code == 200
    assert ok.json()["data"]["torrent"] == "ZDg6YW5ub3VuY2UzOnVybGU="
    assert refused.status_code == 400
    assert refused.json()["type"] == "link_not_from_indexer"


@pytest.mark.asyncio
async def test_with_no_indexers_search_is_off(http: httpx.AsyncClient) -> None:
    app.dependency_overrides[get_search_service] = lambda: SearchService([], httpx.AsyncClient(), timeout_s=1, limit=100)
    try:
        sources = (await http.get("/search/sources")).json()["data"]
        search = await http.get("/search", params={"q": "bunny"})
    finally:
        app.dependency_overrides.clear()

    assert sources == {"enabled": False, "indexers": []}
    assert search.status_code == 404
    assert search.json()["type"] == "search_disabled"
