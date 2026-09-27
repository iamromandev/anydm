"""Adding a playlist as a group, and listing its videos: the routes (v0.5).

Driven through the real app with a fake ``DownloadService``, as ``test_extract_routes`` is.
"""

import uuid
from collections.abc import AsyncIterator, Iterator
from typing import Any

import httpx
import pytest
import pytest_asyncio
from src.config import get_settings
from src.core.success import Meta
from src.data.schema.download import TaskSchema
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.main import app
from src.service import get_download_service

GROUP = uuid.uuid4()


def _schema(**fields: Any) -> TaskSchema:
    base = {
        "id": GROUP,
        "source_url": "https://www.youtube.com/playlist?list=PL1",
        "platform": Platform.SITE,
        "preset": Preset.BEST,
        "kind": Kind.PLAYLIST,
        "status": TaskStatus.PENDING,
    }
    return TaskSchema.model_validate({**base, **fields})


class _FakeDownloads:
    def __init__(self) -> None:
        self.added: list[Any] = []

    async def enqueue_playlist(self, request: Any) -> TaskSchema:
        self.added.append(request)
        return _schema(title=request.title)

    async def list_entries(self, group_id: uuid.UUID, page: int, page_size: int) -> tuple[list[TaskSchema], Meta]:
        return [_schema(id=uuid.uuid4(), kind=Kind.VIDEO, parent_id=group_id, position=1)], Meta(
            page=page, page_size=page_size, total=1, total_pages=1
        )


@pytest.fixture(autouse=True)
def _no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", None)


@pytest.fixture
def downloads() -> Iterator[_FakeDownloads]:
    fake = _FakeDownloads()
    app.dependency_overrides[get_download_service] = lambda: fake
    yield fake
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def http() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as client:
        yield client


def _body(**overrides: Any) -> dict[str, Any]:
    body = {
        "url": "https://www.youtube.com/playlist?list=PL1",
        "extractor": "YoutubeTab",
        "playlist_id": "PL1",
        "title": "A list",
        "preset": "720",
        "entries": [{"index": 1, "id": "v1", "url": "https://youtu.be/v1", "title": "One"}],
    }
    body.update(overrides)
    return body


@pytest.mark.asyncio
async def test_a_playlist_is_added_as_a_group(http: httpx.AsyncClient, downloads: _FakeDownloads) -> None:
    response = await http.post("/download/playlist", json=_body())

    assert response.status_code == 201
    assert response.json()["data"]["kind"] == "playlist"
    assert downloads.added[0].preset == Preset.P720


@pytest.mark.asyncio
async def test_a_group_needs_videos(http: httpx.AsyncClient, downloads: _FakeDownloads) -> None:
    assert (await http.post("/download/playlist", json=_body(entries=[]))).status_code == 422


@pytest.mark.asyncio
async def test_a_video_needs_a_web_address(http: httpx.AsyncClient, downloads: _FakeDownloads) -> None:
    entries = [{"index": 1, "id": "v1", "url": "file:///etc/passwd"}]

    assert (await http.post("/download/playlist", json=_body(entries=entries))).status_code == 422


@pytest.mark.asyncio
async def test_a_groups_videos_are_paged(http: httpx.AsyncClient, downloads: _FakeDownloads) -> None:
    response = await http.get(f"/download/{GROUP}/entries", params={"page": 1, "page_size": 2})

    assert response.status_code == 200
    body = response.json()
    assert body["meta"]["page_size"] == 2
    assert body["data"][0]["parent_id"] == str(GROUP)
