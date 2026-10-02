"""Adding a playlist or a channel tab as a collection, and listing its videos: the routes (v0.5).

Driven through the real app with a fake ``CollectionService``, as ``test_extract_routes`` is.
"""

import uuid
from collections.abc import AsyncIterator, Iterator
from typing import Any

import httpx
import pytest
import pytest_asyncio
from src.config import get_settings
from src.core.success import Meta
from src.data.schema.download import CollectionSchema, DownloadSchema
from src.data.type import CollectionKind, DownloadStatus, MediaKind, Platform, Preset
from src.main import app
from src.service import get_collection_service

COLLECTION = uuid.uuid4()


def _collection(**fields: Any) -> CollectionSchema:
    base: dict[str, Any] = {
        "id": COLLECTION,
        "kind": CollectionKind.PLAYLIST,
        "source_url": "https://www.youtube.com/playlist?list=PL1",
        "extractor": "YoutubeTab",
        "external_id": "PL1",
        "folder": "A list",
        "preset": Preset.BEST,
        "status": DownloadStatus.PENDING,
    }
    base.update(fields)
    return CollectionSchema(**base)


class _FakeCollections:
    def __init__(self) -> None:
        self.added: list[Any] = []
        self.calls: list[tuple[str, uuid.UUID]] = []

    async def add(self, request: Any) -> CollectionSchema:
        self.added.append(request)
        return _collection(title=request.title)

    async def get(self, collection_id: uuid.UUID) -> CollectionSchema:
        return _collection(id=collection_id)

    async def downloads_page(
        self, collection_id: uuid.UUID, page: int, page_size: int
    ) -> tuple[list[DownloadSchema], Meta]:
        video = DownloadSchema(
            id=uuid.uuid4(),
            source_url="https://youtu.be/v1",
            platform=Platform.SITE,
            media_kind=MediaKind.VIDEO,
            status=DownloadStatus.PENDING,
            collection_id=collection_id,
            position=1,
        )
        return [video], Meta(page=page, page_size=page_size, total=1, total_pages=1)

    async def pause(self, collection_id: uuid.UUID) -> CollectionSchema:
        self.calls.append(("pause", collection_id))
        return _collection(id=collection_id, status=DownloadStatus.PAUSED)

    async def resume(self, collection_id: uuid.UUID) -> CollectionSchema:
        self.calls.append(("resume", collection_id))
        return _collection(id=collection_id, status=DownloadStatus.DOWNLOADING)

    async def cancel(self, collection_id: uuid.UUID, *, delete_files: bool) -> None:
        self.calls.append(("cancel" if delete_files else "cancel-keep", collection_id))


@pytest.fixture(autouse=True)
def _no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", None)


@pytest.fixture
def collections() -> Iterator[_FakeCollections]:
    fake = _FakeCollections()
    app.dependency_overrides[get_collection_service] = lambda: fake
    yield fake
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def http() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as client:
        yield client


def _body(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "url": "https://www.youtube.com/playlist?list=PL1",
        "extractor": "YoutubeTab",
        "external_id": "PL1",
        "title": "A list",
        "preset": "720",
        "entries": [{"index": 1, "id": "v1", "url": "https://youtu.be/v1", "title": "One"}],
    }
    body.update(overrides)
    return body


@pytest.mark.asyncio
async def test_a_playlist_is_added_as_a_collection(http: httpx.AsyncClient, collections: _FakeCollections) -> None:
    response = await http.post("/collection", json=_body())

    assert response.status_code == 201
    data = response.json()["data"]
    assert (data["type"], data["kind"]) == ("collection", "playlist")
    assert collections.added[0].preset == Preset.P720


@pytest.mark.asyncio
async def test_a_collection_needs_videos(http: httpx.AsyncClient, collections: _FakeCollections) -> None:
    assert (await http.post("/collection", json=_body(entries=[]))).status_code == 422


@pytest.mark.asyncio
async def test_a_video_needs_a_web_address(http: httpx.AsyncClient, collections: _FakeCollections) -> None:
    entries = [{"index": 1, "id": "v1", "url": "file:///etc/passwd"}]

    assert (await http.post("/collection", json=_body(entries=entries))).status_code == 422


@pytest.mark.asyncio
async def test_a_collections_videos_are_paged(http: httpx.AsyncClient, collections: _FakeCollections) -> None:
    response = await http.get(f"/collection/{COLLECTION}/downloads", params={"page": 1, "page_size": 2})

    assert response.status_code == 200
    body = response.json()
    assert body["meta"]["page_size"] == 2
    assert body["data"][0]["collection_id"] == str(COLLECTION)


@pytest.mark.asyncio
async def test_a_collection_is_fetched_paused_resumed_and_removed(
    http: httpx.AsyncClient, collections: _FakeCollections
) -> None:
    assert (await http.get(f"/collection/{COLLECTION}")).json()["data"]["id"] == str(COLLECTION)
    assert (await http.post(f"/collection/{COLLECTION}/pause")).json()["data"]["status"] == "paused"
    assert (await http.post(f"/collection/{COLLECTION}/resume")).json()["data"]["status"] == "downloading"
    assert (await http.delete(f"/collection/{COLLECTION}", params={"delete_files": False})).status_code == 204

    assert collections.calls == [
        ("pause", COLLECTION),
        ("resume", COLLECTION),
        ("cancel-keep", COLLECTION),
    ]
