"""The API key gate, driven through the real app's routing.

Nothing here opens a database: lifespan never runs under ``ASGITransport``, and
the two routes that would need a service get fakes through FastAPI's own
override mechanism.
"""

import logging
from collections.abc import AsyncIterator, Iterator
from typing import Any

import httpx
import pytest
import pytest_asyncio
from pydantic import SecretStr
from src.config import get_settings
from src.core.auth import RedactApiKey, key_matches, redact
from src.data.schema.health import HealthSchema
from src.main import app
from src.service import get_health_service, get_stream_service

KEY = "s3cret-key"

PLAYLIST = "#EXTM3U\n#EXTINF:6.000,\nsegment_0.ts\n#EXTINF:6.000,\nsegment_1.ts\n#EXT-X-ENDLIST\n"


class _FakeHealth:
    async def check_health(self, **_: Any) -> HealthSchema:
        return HealthSchema.model_construct()


class _FakeStream:
    def get_session(self, session_id: str) -> object:
        return object()

    def playlist_text(self, session: object) -> str:
        return PLAYLIST


@pytest.fixture(autouse=True)
def _fakes() -> Iterator[None]:
    app.dependency_overrides[get_health_service] = _FakeHealth
    app.dependency_overrides[get_stream_service] = _FakeStream
    yield
    app.dependency_overrides.clear()


@pytest.fixture
def key_required(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", SecretStr(KEY))


@pytest_asyncio.fixture
async def client() -> AsyncIterator[httpx.AsyncClient]:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://api.test") as http:
        yield http


PLAYLIST_PATH = "/stream/abc/playlist.m3u8"


@pytest.mark.asyncio
async def test_with_no_key_configured_nothing_changes(
    client: httpx.AsyncClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Unset explicitly: a developer's own .env may well set one.
    monkeypatch.setattr(get_settings(), "api_key", None)
    assert (await client.get("/settings")).status_code == 200


@pytest.mark.asyncio
@pytest.mark.usefixtures("key_required")
async def test_a_missing_key_is_a_401_in_the_usual_envelope(client: httpx.AsyncClient) -> None:
    response = await client.get("/settings")

    assert response.status_code == 401
    assert response.json()["code"] == 401


@pytest.mark.asyncio
@pytest.mark.usefixtures("key_required")
async def test_a_wrong_key_is_a_401(client: httpx.AsyncClient) -> None:
    response = await client.get("/settings", headers={"X-API-Key": "guess"})

    assert response.status_code == 401


@pytest.mark.asyncio
@pytest.mark.usefixtures("key_required")
async def test_the_header_is_accepted(client: httpx.AsyncClient) -> None:
    response = await client.get("/settings", headers={"X-API-Key": KEY})

    assert response.status_code == 200


@pytest.mark.asyncio
@pytest.mark.usefixtures("key_required")
async def test_the_query_is_refused_where_a_header_could_have_been_sent(
    client: httpx.AsyncClient,
) -> None:
    # A key in a URL ends up in history, logs and Referer headers, so it is
    # only accepted where the browser leaves no alternative.
    response = await client.get("/settings", params={"api_key": KEY})

    assert response.status_code == 401


@pytest.mark.asyncio
@pytest.mark.usefixtures("key_required")
async def test_the_query_is_accepted_where_a_browser_cannot_send_headers(
    client: httpx.AsyncClient,
) -> None:
    response = await client.get(PLAYLIST_PATH, params={"api_key": KEY})

    assert response.status_code == 200


@pytest.mark.asyncio
@pytest.mark.usefixtures("key_required")
async def test_a_wrong_query_key_is_a_401(client: httpx.AsyncClient) -> None:
    response = await client.get(PLAYLIST_PATH, params={"api_key": "guess"})

    assert response.status_code == 401


@pytest.mark.asyncio
async def test_an_empty_key_counts_as_none(
    client: httpx.AsyncClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    # ``API_KEY=`` in an .env copied from the example must not lock anyone out.
    monkeypatch.setattr(get_settings(), "api_key", SecretStr(""))
    assert (await client.get("/settings")).status_code == 200


@pytest.mark.asyncio
@pytest.mark.usefixtures("key_required")
async def test_health_needs_no_key(client: httpx.AsyncClient) -> None:
    assert (await client.get("/health/check")).status_code == 200


@pytest.mark.asyncio
@pytest.mark.usefixtures("key_required")
async def test_a_playlist_fetched_by_query_hands_the_key_to_its_segments(
    client: httpx.AsyncClient,
) -> None:
    # Safari's native player fetches segments by the playlist's relative URIs,
    # which carry no query of their own.
    response = await client.get(PLAYLIST_PATH, params={"api_key": KEY})

    segments = [line for line in response.text.splitlines() if not line.startswith("#")]
    assert segments == [f"segment_0.ts?api_key={KEY}", f"segment_1.ts?api_key={KEY}"]


@pytest.mark.asyncio
@pytest.mark.usefixtures("key_required")
async def test_a_playlist_fetched_by_header_is_left_alone(client: httpx.AsyncClient) -> None:
    response = await client.get(PLAYLIST_PATH, headers={"X-API-Key": KEY})

    assert response.text == PLAYLIST


def test_key_matches() -> None:
    assert key_matches(KEY, KEY)
    assert not key_matches(KEY, "guess")
    assert not key_matches(KEY, None)
    assert not key_matches(KEY, "")
    assert not key_matches(KEY, "ключ")  # not ASCII, and must not raise


def test_redact_hides_the_key_and_keeps_the_rest() -> None:
    assert redact("/download/x/file?api_key=abc&inline=1") == "/download/x/file?api_key=***&inline=1"
    assert redact("/stream/events?x=1&api_key=abc") == "/stream/events?x=1&api_key=***"
    assert redact("/settings") == "/settings"


def test_the_access_log_filter_redacts_the_request_line() -> None:
    # uvicorn's access record: (client, method, path with query, http version, status).
    record = logging.LogRecord(
        "uvicorn.access",
        logging.INFO,
        __file__,
        0,
        '%s - "%s %s HTTP/%s" %d',
        ("127.0.0.1:5000", "GET", "/download/events?api_key=abc", "1.1", 200),
        None,
    )

    assert RedactApiKey().filter(record)
    assert "abc" not in record.getMessage()
