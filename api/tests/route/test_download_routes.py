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
from src.data.schema.transfer import (
    BatchItemSchema,
    BatchPreviewSchema,
    BatchResult,
    CollectionSchema,
    DownloadSchema,
)
from src.data.type import CollectionKind, DownloadStatus, MediaKind, Platform, Preset
from src.main import app
from src.service import get_download_service
from src.service.download.duplicate import already_held

from tests.service.download.memory import download_row


class StubDownloads:
    def __init__(self) -> None:
        self.playback: list[tuple[Any, ...]] = []
        self.files: list[tuple[uuid.UUID, int | None]] = []
        #: Each direct add: the address and whether a duplicate was allowed.
        self.added: list[tuple[str, bool]] = []
        #: What the next direct add raises, as the real service does for an address it holds.
        self.held: Any = None
        #: Each batch add: its lines, pattern, preset and whether duplicates were allowed.
        self.batches: list[tuple[Any, ...]] = []

    async def enqueue_url(self, url: str, *, allow_duplicate: bool = False) -> DownloadSchema:
        self.added.append((url, allow_duplicate))
        if self.held is not None and not allow_duplicate:
            raise already_held(self.held)
        return DownloadSchema(
            id=uuid.uuid4(), url=url, platform=Platform.DIRECT, media_kind=MediaKind.FILE, status=DownloadStatus.PENDING
        )

    def preview_batch(self, lines: Any, pattern: Any) -> BatchPreviewSchema:
        return BatchPreviewSchema(count=2, urls=["https://x.test/a1", "https://x.test/a2"])

    async def add_batch(self, lines: Any, pattern: Any, preset: Any, *, allow_duplicate: bool = False) -> Any:
        self.batches.append((lines, pattern, preset, allow_duplicate))
        return [
            BatchItemSchema(url="https://x.test/a1", result=BatchResult.ADDED, download_id=uuid.uuid4()),
            BatchItemSchema(url="https://x.test/a2", result=BatchResult.ERROR, message="no"),
        ]

    async def list_items(
        self, page: int, page_size: int, group: str = "all", sort: str = "-created_at", category: Any = None
    ) -> Any:
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


@pytest.mark.asyncio
async def test_an_address_already_held_answers_409_naming_its_download(
    http: httpx.AsyncClient, downloads: StubDownloads
) -> None:
    held = download_row(url="https://cdn.test/a.iso", title="a.iso", status=DownloadStatus.COMPLETED)
    downloads.held = held

    response = await http.post("/download/url", json={"url": "https://cdn.test/a.iso"})

    assert response.status_code == 409
    body = response.json()
    assert (body["type"], body["message"]) == ("conflict", "Already in your list: a.iso (completed)")
    (detail,) = body["details"]
    assert (detail["subject"], detail["description"], detail["fields"]) == (str(held.id), "a.iso", ["completed"])


@pytest.mark.asyncio
async def test_allow_duplicate_reaches_the_service(http: httpx.AsyncClient, downloads: StubDownloads) -> None:
    downloads.held = download_row(url="https://cdn.test/a.iso", title="a.iso")

    response = await http.post("/download/url", json={"url": "https://cdn.test/a.iso", "allow_duplicate": True})

    assert response.status_code == 201
    assert downloads.added == [("https://cdn.test/a.iso", True)]


@pytest.mark.asyncio
async def test_a_batch_answers_for_every_link_and_is_a_200_whatever_the_mix(
    http: httpx.AsyncClient, downloads: StubDownloads
) -> None:
    response = await http.post("/download/batch", json={"pattern": "https://x.test/a[1-2]", "preset": "720"})

    assert response.status_code == 200
    assert [item["result"] for item in response.json()["data"]] == ["added", "error"]
    assert response.json()["data"][1]["message"] == "no"
    assert downloads.batches == [(None, "https://x.test/a[1-2]", Preset.P720, False)]


@pytest.mark.asyncio
async def test_a_batch_of_lines_reaches_the_service_with_allow_duplicate(
    http: httpx.AsyncClient, downloads: StubDownloads
) -> None:
    await http.post("/download/batch", json={"lines": ["https://x.test/a"], "allow_duplicate": True})

    assert downloads.batches == [(["https://x.test/a"], None, Preset.BEST, True)]


@pytest.mark.asyncio
@pytest.mark.parametrize("body", [{}, {"lines": ["https://x.test/a"], "pattern": "https://x.test/a"}])
async def test_a_batch_needs_exactly_one_of_lines_or_a_pattern(
    http: httpx.AsyncClient, downloads: StubDownloads, body: dict[str, Any]
) -> None:
    for path in ("/download/batch", "/download/batch/preview"):
        assert (await http.post(path, json=body)).status_code == 422
    assert downloads.batches == []


@pytest.mark.asyncio
async def test_a_preview_names_the_links_and_is_not_swallowed_by_the_download_id_route(
    http: httpx.AsyncClient, downloads: StubDownloads
) -> None:
    response = await http.post("/download/batch/preview", json={"pattern": "https://x.test/a[1-2]"})

    assert response.status_code == 200
    assert response.json()["data"] == {"count": 2, "urls": ["https://x.test/a1", "https://x.test/a2"]}
    assert downloads.batches == []
