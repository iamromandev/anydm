"""GET /search/youtube through the real app, with a fake site client behind it."""

from collections.abc import AsyncIterator, Iterator

import httpx
import pytest
import pytest_asyncio
from src.config import get_settings
from src.lib.site import error as site_error
from src.lib.site.client import VideoHit
from src.main import app
from src.service import get_video_search_service
from src.service.search.video_service import VideoSearchService

from tests.sites import FakeSiteClient, site_info

HIT = VideoHit(id="a", url="https://www.youtube.com/watch?v=a", title="Big Buck Bunny", channel="Blender", duration=596)


@pytest.fixture(autouse=True)
def _no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", None)


@pytest.fixture
def fake() -> Iterator[FakeSiteClient]:
    fake = FakeSiteClient(site_info("youtube"), hits=[HIT])
    app.dependency_overrides[get_video_search_service] = lambda: VideoSearchService(fake)
    yield fake
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def http() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as client:
        yield client


@pytest.mark.asyncio
async def test_it_answers_the_videos(http: httpx.AsyncClient, fake: FakeSiteClient) -> None:
    body = (await http.get("/search/youtube", params={"q": "  bunny "})).json()["data"]

    assert body["results"][0]["title"] == "Big Buck Bunny"
    assert body["results"][0]["channel"] == "Blender"
    assert body["results"][0]["duration"] == 596
    assert "published" not in body["results"][0]
    assert fake.searched == [("bunny", 20)]


@pytest.mark.asyncio
@pytest.mark.parametrize("params", [{}, {"q": ""}, {"q": " a "}, {"q": "x" * 201}, {"q": "ok", "limit": 0}, {"q": "ok", "limit": 31}])
async def test_a_bad_question_is_422(http: httpx.AsyncClient, fake: FakeSiteClient, params: dict[str, str | int]) -> None:
    assert (await http.get("/search/youtube", params=params)).status_code == 422
    assert fake.searched == []


@pytest.mark.asyncio
async def test_the_limit_is_passed_on(http: httpx.AsyncClient, fake: FakeSiteClient) -> None:
    await http.get("/search/youtube", params={"q": "bunny", "limit": 5})

    assert fake.searched == [("bunny", 5)]


@pytest.mark.asyncio
async def test_a_failure_is_the_sites_error(http: httpx.AsyncClient, fake: FakeSiteClient) -> None:
    fake.search_fail = site_error.extraction_failed("HTTP Error 429")

    response = await http.get("/search/youtube", params={"q": "bunny"})

    assert response.status_code == 502
    assert "Extraction failed" in response.json()["message"]
