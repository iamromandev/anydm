"""``GET /extract/entries``'s frames: a playlist's videos as the site pages through them."""

import asyncio
import threading
import time
from collections.abc import Callable, Iterator
from typing import Any

import pytest
from src.data.type import DownloadStatus
from src.lib.site import error as site_error
from src.lib.site.client import PlaylistEntry
from src.service.extract.listing_service import BATCH_SIZE, ListingService

from tests.sites import FakeSiteClient, HeldVideos, site_info

URL = "https://www.youtube.com/playlist?list=PL1"


def _entry(n: int, **fields: Any) -> PlaylistEntry:
    return PlaylistEntry(index=n, id=f"v{n}", url=f"https://youtu.be/v{n}", extractor="Youtube", title=f"Video {n}", **fields)


def _service(client: Any, held: HeldVideos | None = None, **kwargs: Any) -> ListingService:
    return ListingService(client=client, repo=held or HeldVideos(), **kwargs)


async def _frames(service: ListingService, **kwargs: Any) -> list[tuple[str, Any]]:
    return [frame async for frame in service.frames(URL, **kwargs)]


def _sent(frames: list[tuple[str, Any]]) -> list[dict[str, Any]]:
    return [entry for event, data in frames if event == "entries" for entry in data]


@pytest.mark.asyncio
async def test_a_listing_sends_its_videos_then_done() -> None:
    frames = await _frames(_service(FakeSiteClient(site_info("youtube"), listing=[_entry(1), _entry(2)])))

    assert frames[-1] == ("done", {"count": 2})
    assert _sent(frames)[0] == {
        "index": 1,
        "id": "v1",
        "url": "https://youtu.be/v1",
        "title": "Video 1",
        "duration": None,
        "thumbnail": None,
        "timestamp": None,
        "available": True,
        "have": None,
    }
    assert [e["id"] for e in _sent(frames)] == ["v1", "v2"]


@pytest.mark.asyncio
async def test_no_frame_holds_more_than_a_batch() -> None:
    listing = [_entry(n) for n in range(1, 251)]

    frames = await _frames(_service(FakeSiteClient(site_info("youtube"), listing=listing)))

    batches = [data for event, data in frames if event == "entries"]
    assert all(len(batch) <= BATCH_SIZE for batch in batches)
    assert [e["index"] for e in _sent(frames)] == list(range(1, 251))


@pytest.mark.asyncio
async def test_the_limit_reaches_the_client() -> None:
    client = FakeSiteClient(site_info("youtube"), listing=[_entry(n) for n in range(1, 6)])

    frames = await _frames(_service(client), limit=3)

    assert frames[-1] == ("done", {"count": 3})


@pytest.mark.asyncio
async def test_have_says_what_is_already_held() -> None:
    held = HeldVideos(
        {
            ("Youtube", "v1"): DownloadStatus.COMPLETED,
            ("Youtube", "v2"): DownloadStatus.PAUSED,
            ("Youtube", "v3"): DownloadStatus.FAILED,
        }
    )
    client = FakeSiteClient(site_info("youtube"), listing=[_entry(n) for n in range(1, 5)])

    frames = await _frames(_service(client, held))

    assert [e["have"] for e in _sent(frames)] == ["complete", "queued", "failed", None]


@pytest.mark.asyncio
async def test_a_site_failure_ends_the_stream_with_failed() -> None:
    client = FakeSiteClient(
        site_info("youtube"), listing=[_entry(1)], list_fail=site_error.media_unavailable("This playlist is private")
    )

    frames = await _frames(_service(client))

    assert [e["id"] for e in _sent(frames)] == ["v1"]
    event, data = frames[-1]
    assert event == "failed"
    assert data["type"] == "does_not_exist"
    assert data["message"] == "Media unavailable: This playlist is private"


class _StuckClient:
    """A site that never answers, until released."""

    def __init__(self) -> None:
        self.release = threading.Event()
        self.saw_stop = threading.Event()

    def list_entries(self, url: str, *, limit: int, should_stop: Callable[[], bool]) -> Iterator[PlaylistEntry]:
        self.release.wait(5)
        if should_stop():
            self.saw_stop.set()
        yield from ()


@pytest.mark.asyncio
async def test_a_site_that_stops_answering_ends_the_stream() -> None:
    client = _StuckClient()

    frames = await _frames(_service(client, stall_s=0.05))
    client.release.set()

    event, data = frames[-1]
    assert event == "failed"
    assert data["message"] == "Extraction failed: The site stopped answering"
    assert await asyncio.to_thread(client.saw_stop.wait, 2)


class _EndlessClient:
    """A listing with no end, which only a stop request ends."""

    def __init__(self) -> None:
        self.stopped = threading.Event()

    def list_entries(self, url: str, *, limit: int, should_stop: Callable[[], bool]) -> Iterator[PlaylistEntry]:
        n = 0
        while not should_stop():
            n += 1
            yield _entry(n)
            time.sleep(0.001)
        self.stopped.set()


@pytest.mark.asyncio
async def test_closing_the_stream_stops_the_listing() -> None:
    client = _EndlessClient()
    frames = _service(client).frames(URL)

    first = await anext(frames)
    await frames.aclose()

    assert first[0] == "entries"
    assert await asyncio.to_thread(client.stopped.wait, 2)
