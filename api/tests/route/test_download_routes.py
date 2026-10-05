"""The download routes through the real app, with a stub service behind them.

Like ``test_source_routes.py``: ``ASGITransport`` skips the lifespan, so no Postgres.
"""

import uuid
from collections.abc import AsyncIterator, Iterator
from typing import Any

import httpx
import pytest
import pytest_asyncio
from src.config import get_settings
from src.core.success import Meta
from src.data.schema.play import PlaybackSchema
from src.data.schema.transfer import CollectionSchema, DownloadSchema
from src.data.type import CollectionKind, DownloadStatus, MediaKind, Platform, Preset
from src.main import app
from src.service import get_download_service


class StubDownloads:
    def __init__(self) -> None:
        self.playback: list[tuple[Any, ...]] = []
        self.files: list[tuple[uuid.UUID, int | None]] = []

    async def list_items(self, page: int, page_size: int, group: str = "all", sort: str = "-created_at") -> Any:
        return [
            DownloadSchema(
                id=uuid.uuid4(),
                url="u",
                platform=Platform.DIRECT,
                media_kind=MediaKind.FILE,
                status=DownloadStatus.PENDING,
            ),
            CollectionSchema(
                id=uuid.uuid4(),
                url="https://www.youtube.com/playlist?list=PL",
                kind=CollectionKind.PLAYLIST,
                extractor="YoutubeTab",
                external_id="PL",
                folder="Talks",
                preset=Preset.BEST,
                status=DownloadStatus.PAUSED,
            ),
        ], Meta(page=1, page_size=50, total=2, total_pages=1)

    async def save_playback(
        self, download_id: uuid.UUID, file_index: int | None, *, position_seconds: float, duration_seconds: float
    ) -> PlaybackSchema:
        self.playback.append((download_id, file_index, position_seconds))
        return PlaybackSchema(position_seconds=position_seconds, duration_seconds=duration_seconds)

    async def resolve_file(self, download_id: uuid.UUID, file_index: int | None) -> Any:
        self.files.append((download_id, file_index))
        return __file__, "test_download_routes.py", "text/x-python"


@pytest.fixture(autouse=True)
def _no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", None)


@pytest.fixture
def downloads() -> Iterator[StubDownloads]:
    stub = StubDownloads()
    app.dependency_overrides[get_download_service] = lambda: stub
    yield stub
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def http() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as client:
        yield client


@pytest.mark.asyncio
async def test_the_list_is_tagged(http: httpx.AsyncClient, downloads: StubDownloads) -> None:
    body = (await http.get("/download")).json()
    assert [item["type"] for item in body["data"]] == ["download", "collection"]


@pytest.mark.asyncio
async def test_playback_is_saved_per_file(http: httpx.AsyncClient, downloads: StubDownloads) -> None:
    download_id = uuid.uuid4()
    response = await http.put(
        f"/download/{download_id}/file/2/playback", json={"position_seconds": 12, "duration_seconds": 60}
    )
    assert response.status_code == 200
    assert downloads.playback == [(download_id, 2, 12.0)]


@pytest.mark.asyncio
async def test_every_download_s_file_is_fetched_by_index(http: httpx.AsyncClient, downloads: StubDownloads) -> None:
    download_id = uuid.uuid4()
    response = await http.get(f"/download/{download_id}/file/0")
    assert response.status_code == 200
    assert downloads.files == [(download_id, 0)]
    # The single-file route without an index is gone.
    assert (await http.get(f"/download/{download_id}/file")).status_code in (404, 405)
