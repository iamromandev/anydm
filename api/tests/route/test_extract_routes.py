"""``POST /extract`` through the real app, with a fake site client (v0.5 part 1)."""

from collections.abc import AsyncIterator, Callable, Iterator

import httpx
import pytest
import pytest_asyncio
from src.config import get_settings
from src.lib.site.client import PlaylistInfo, Tab
from src.main import app
from src.service import ExtractService, get_extract_service

from tests.sites import FakeSiteClient, site_info


@pytest.fixture(autouse=True)
def _no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    # A developer's own .env may well set one.
    monkeypatch.setattr(get_settings(), "api_key", None)


@pytest.fixture
def use_client() -> Iterator[Callable[[FakeSiteClient], None]]:
    def use(fake: FakeSiteClient) -> None:
        app.dependency_overrides[get_extract_service] = lambda: ExtractService(client=fake)

    yield use
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def http() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as client:
        yield client


@pytest.mark.asyncio
async def test_a_video_answers_media(http: httpx.AsyncClient, use_client: Callable[[FakeSiteClient], None]) -> None:
    use_client(FakeSiteClient(site_info("vimeo")))

    body = (await http.post("/extract", json={"url": "http://vimeo.com/75629013"})).json()

    assert (body["data"]["type"], body["data"]["id"]) == ("media", "75629013")


@pytest.mark.asyncio
async def test_a_channel_answers_its_tabs(http: httpx.AsyncClient, use_client: Callable[[FakeSiteClient], None]) -> None:
    channel = PlaylistInfo(extractor="YoutubeTab", id="@x", title="X", tabs=[Tab("Videos", "https://y.test/@x/videos")])
    use_client(FakeSiteClient(site_info("youtube"), playlist=channel))

    body = (await http.post("/extract", json={"url": "https://y.test/@x"})).json()

    assert body["data"]["type"] == "channel"
    assert body["data"]["tabs"] == [{"name": "Videos", "url": "https://y.test/@x/videos"}]
