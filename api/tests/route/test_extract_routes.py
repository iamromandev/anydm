"""``POST /extract`` through the real app, with a fake site client (v0.5 part 1)."""

from collections.abc import AsyncIterator, Callable, Iterator

import httpx
import pytest
import pytest_asyncio
from pydantic import SecretStr
from src.config import get_settings
from src.lib.site.client import PlaylistEntry, PlaylistInfo, Tab
from src.main import app
from src.service import ExtractService, ListingService, get_extract_service, get_listing_service
from src.service.extract.listing_service import LISTING_LIMIT

from tests.sites import FakeSiteClient, HeldVideos, site_info


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


@pytest.fixture
def use_listing() -> Iterator[Callable[[FakeSiteClient], None]]:
    def use(fake: FakeSiteClient) -> None:
        app.dependency_overrides[get_listing_service] = lambda: ListingService(client=fake, repo=HeldVideos())

    yield use
    app.dependency_overrides.clear()


def _video(n: int) -> PlaylistEntry:
    return PlaylistEntry(index=n, id=f"v{n}", url=f"https://youtu.be/v{n}", extractor="Youtube")


@pytest.mark.asyncio
async def test_entries_stream_as_server_sent_events(
    http: httpx.AsyncClient, use_listing: Callable[[FakeSiteClient], None]
) -> None:
    use_listing(FakeSiteClient(site_info("youtube"), listing=[_video(1), _video(2)]))

    response = await http.get("/extract/entries", params={"url": "https://y.test/list"})

    assert response.headers["content-type"].startswith("text/event-stream")
    assert "event: entries" in response.text
    assert "event: done" in response.text
    assert '"count": 2' in response.text


@pytest.mark.asyncio
async def test_a_limit_past_the_cap_is_refused(
    http: httpx.AsyncClient, use_listing: Callable[[FakeSiteClient], None]
) -> None:
    use_listing(FakeSiteClient(site_info("youtube")))

    response = await http.get("/extract/entries", params={"url": "https://y.test/list", "limit": LISTING_LIMIT + 1})

    assert response.status_code == 422


@pytest.mark.asyncio
async def test_the_stream_takes_its_key_in_the_query(
    http: httpx.AsyncClient, use_listing: Callable[[FakeSiteClient], None], monkeypatch: pytest.MonkeyPatch
) -> None:
    # An EventSource can't send a header.
    monkeypatch.setattr(get_settings(), "api_key", SecretStr("s3cret-key"))
    use_listing(FakeSiteClient(site_info("youtube"), listing=[_video(1)]))

    response = await http.get("/extract/entries", params={"url": "https://y.test/list", "api_key": "s3cret-key"})

    assert response.status_code == 200
