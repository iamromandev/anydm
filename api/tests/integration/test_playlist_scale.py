"""Collections at the size a channel archive reaches (part 3): 5,000 videos.

The time bounds are ceilings that catch a missing index or a per-row loop,
not benchmarks.
"""

import time
from typing import Any

import pytest
from src.data.db.model import Collection, Download
from src.data.repo import CollectionDatabaseRepo, DownloadDatabaseRepo
from src.data.repo.download.interface.collection import EntryRow
from src.data.type import CollectionKind, DownloadStatus, MediaKind, Platform, Preset
from src.service.download.collection_totals import counts_of

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]

SIZE = 5_000

COLLECTION: dict[str, Any] = {
    "kind": CollectionKind.CHANNEL,
    "source_url": "https://www.youtube.com/@TED/videos",
    "extractor": "YoutubeTab",
    "external_id": "UCAuUUnT6oDeKwE6v1NGQxug",
    "title": "TED · Videos",
    "folder": "TED_Videos",
    "preset": Preset.P720,
}


def _entry(n: int) -> EntryRow:
    return EntryRow(
        download={
            "source_url": f"https://www.youtube.com/watch?v=v{n:05d}",
            "platform": Platform.SITE,
            "media_kind": MediaKind.VIDEO,
            "status": DownloadStatus.PENDING,
            "title": f"Talk {n}",
            "position": n,
        },
        site={"extractor": "Youtube", "video_id": f"v{n:05d}", "preset": Preset.P720},
        filename="",
    )


async def _big_collection() -> Collection:
    return await CollectionDatabaseRepo().create_with_entries(COLLECTION, [_entry(n) for n in range(1, SIZE + 1)])


async def test_adding_five_thousand_videos_is_one_quick_insert(db: None) -> None:
    started = time.monotonic()
    collection = await _big_collection()
    elapsed = time.monotonic() - started

    assert await Download.filter(collection_id=collection.id).count() == SIZE
    assert elapsed < 10, f"{elapsed:.1f}s for {SIZE} videos"


async def test_claim_takes_the_collection_in_order_without_scanning_slowly(db: None) -> None:
    await _big_collection()
    repo = DownloadDatabaseRepo()

    started = time.monotonic()
    claimed = [await repo.claim_next() for _ in range(3)]
    elapsed = time.monotonic() - started

    assert [row and row.position for row in claimed] == [1, 2, 3]
    assert elapsed < 2, f"{elapsed:.2f}s for three claims"


async def test_the_last_page_of_videos_is_as_quick_as_the_first(db: None) -> None:
    collection = await _big_collection()

    started = time.monotonic()
    rows, meta = await CollectionDatabaseRepo().downloads_page(collection.id, page=100, page_size=50)
    elapsed = time.monotonic() - started

    assert [rows[0].position, rows[-1].position] == [4_951, 5_000]
    assert meta.total_pages == 100
    assert elapsed < 1, f"{elapsed:.2f}s for the last page"


async def test_the_totals_count_five_thousand_videos(db: None) -> None:
    collection = await _big_collection()
    await Download.filter(collection_id=collection.id, position__lte=1_000).update(status=DownloadStatus.COMPLETE)

    started = time.monotonic()
    counts = counts_of(await CollectionDatabaseRepo().member_rows(collection.id))
    elapsed = time.monotonic() - started

    assert (counts.total, counts.complete, counts.active) == (SIZE, 1_000, 4_000)
    assert elapsed < 1, f"{elapsed:.2f}s for the totals"


async def test_the_list_counts_a_big_collection_quickly(db: None) -> None:
    """The ``list_item`` view computes a collection's status and bytes from its videos."""
    await _big_collection()

    started = time.monotonic()
    items, _ = await DownloadDatabaseRepo().list_items(1, 50, None, "-created_at", {})
    elapsed = time.monotonic() - started

    assert [kind for kind, _ in items] == ["collection"]
    assert elapsed < 1, f"{elapsed:.2f}s for the list"


async def test_joining_a_big_collection_is_quick(db: None) -> None:
    collection = await _big_collection()
    repo = CollectionDatabaseRepo()

    started = time.monotonic()
    held = await repo.held(collection.id)
    await repo.add_entries(collection, [_entry(n) for n in range(SIZE + 1, SIZE + 51)])
    elapsed = time.monotonic() - started

    assert len(held) == SIZE
    assert await Download.filter(collection_id=collection.id).count() == SIZE + 50
    assert elapsed < 3, f"{elapsed:.2f}s to join"
