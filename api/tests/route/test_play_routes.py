"""Playing a finished download: the routes (#94).

Driven through the real app with a fake ``StreamService``, as ``test_auth`` is.
"""

import uuid
from collections.abc import AsyncIterator, Iterator
from typing import Any

import httpx
import pytest
import pytest_asyncio
from src.config import get_settings
from src.main import app
from src.service import get_stream_service
from src.service.stream.stream_service import MediaInfo

TASK = uuid.uuid4()


class _FakeStream:
    def __init__(self) -> None:
        self.calls: list[tuple[str, Any]] = []

    async def media_info(self, task_id: uuid.UUID, file_index: int | None) -> MediaInfo:
        self.calls.append(("media_info", (task_id, file_index)))
        return MediaInfo(
            file_index=file_index if file_index is not None else (3 if task_id != TASK else None),
            filename="Movie.mp4",
            duration_seconds=30.0,
            has_video=True,
            media_type='video/mp4; codecs="avc1.640028, mp4a.40.2"',
        )

    async def start_task_session(self, task_id: uuid.UUID, file_index: int | None) -> Any:
        self.calls.append(("start_task_session", (task_id, file_index)))
        return type("S", (), {"id": "s1", "status": "ready", "duration_seconds": 30.0, "has_video": True})()

    async def start_session(self, url: str) -> Any:
        self.calls.append(("start_session", url))
        return type("S", (), {"id": "s2", "status": "ready", "duration_seconds": 1.0, "has_video": True})()


@pytest.fixture
def stream(monkeypatch: pytest.MonkeyPatch) -> Iterator[_FakeStream]:
    fake = _FakeStream()
    monkeypatch.setattr(get_settings(), "api_key", None)
    app.dependency_overrides[get_stream_service] = lambda: fake
    yield fake
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def client() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as http:
        yield http


@pytest.mark.asyncio
async def test_media_names_the_file_and_where_to_fetch_it(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    response = await client.get(f"/download/{TASK}/media")

    assert response.status_code == 200
    # A download's one file has no index, and the envelope leaves out what is None.
    assert response.json()["data"] == {
        "filename": "Movie.mp4",
        "duration_seconds": 30.0,
        "has_video": True,
        "media_type": 'video/mp4; codecs="avc1.640028, mp4a.40.2"',
        "file_url": f"/download/{TASK}/file",
    }
    assert stream.calls == [("media_info", (TASK, None))]


@pytest.mark.asyncio
async def test_a_torrents_media_points_at_that_file(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    other = uuid.uuid4()
    response = await client.get(f"/download/{other}/media", params={"file_index": 5})

    assert response.json()["data"]["file_url"] == f"/download/{other}/file/5"
    assert stream.calls == [("media_info", (other, 5))]


@pytest.mark.asyncio
async def test_a_session_starts_from_a_task(client: httpx.AsyncClient, stream: _FakeStream) -> None:
    response = await client.post("/stream/start", json={"task_id": str(TASK), "file_index": 2})

    assert response.status_code == 201
    assert response.json()["data"]["session_id"] == "s1"
    assert stream.calls == [("start_task_session", (TASK, 2))]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "body",
    [
        {"task_id": str(TASK), "url": "https://example.com/a.mp4"},
        {"url": "https://example.com/a.mp4", "torrent": "magnet:?xt=urn:btih:abc"},
        {"url": "https://example.com/a.mp4", "file_index": 1},
        {"file_index": 1},
        {},
    ],
)
async def test_a_start_names_exactly_one_source(
    client: httpx.AsyncClient, stream: _FakeStream, body: dict[str, Any]
) -> None:
    response = await client.post("/stream/start", json=body)

    assert response.status_code in (400, 422)
    assert stream.calls == []
