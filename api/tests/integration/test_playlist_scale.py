"""Collections at the size a channel archive reaches (part 3): 5,000 videos.

The time bounds are ceilings that catch a missing index or a per-row loop,
not benchmarks.
"""

import time

import pytest
from src.data.db.model import Download, Media
from src.data.repo import CollectionDatabaseRepo, DownloadDatabaseRepo
from src.data.repo.download.interface.collection import EntryRow
from src.data.type import DownloadStatus, MediaKind, Preset
from src.service.download.collection_totals import counts_of

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]

SIZE = 5_000

CHANNEL = "https://www.youtube.com/@TED/videos"


def _entry(n: int) -> EntryRow:
    return EntryRow(
        url=f"https://www.youtube.com/watch?v=v{n:010d}",
        download={"status": DownloadStatus.PENDING},
        media={"title": f"Talk {n}", "kind": MediaKind.VIDEO, "preset": Preset.P720, "playlist_index": n},
        filename="",
    )


async def _big_collection() -> Download:
    return await CollectionDatabaseRepo().create_with_entries(
        url=CHANNEL,
        provider="Youtube",
        collection={"status": DownloadStatus.PENDING},
        media={"kind": MediaKind.CHANNEL, "title": "TED · Videos", "preset": Preset.P720},
        entries=[_entry(n) for n in range(1, SIZE + 1)],
    )


async def test_adding_five_thousand_videos_is_one_quick_insert(db: None) -> None:
    started = time.monotonic()
    collection = await _big_collection()
    elapsed = time.monotonic() - started

    assert await Download.filter(parent_id=collection.id).count() == SIZE
    assert elapsed < 10, f"{elapsed:.1f}s for {SIZE} videos"


async def test_claim_takes_the_collection_in_order_without_scanning_slowly(db: None) -> None:
    await _big_collection()
    repo = DownloadDatabaseRepo()

    started = time.monotonic()
    claimed = [await repo.claim_next() for _ in range(3)]
    elapsed = time.monotonic() - started

    assert [row.media.playlist_index if row and row.media else None for row in claimed] == [1, 2, 3]
    assert elapsed < 2, f"{elapsed:.2f}s for three claims"


async def test_the_last_page_of_videos_is_as_quick_as_the_first(db: None) -> None:
    collection = await _big_collection()

    started = time.monotonic()
    rows, meta = await CollectionDatabaseRepo().downloads_page(collection.id, page=100, page_size=50)
    elapsed = time.monotonic() - started

    assert [row.media.playlist_index if row.media else None for row in (rows[0], rows[-1])] == [4_951, 5_000]
    assert meta.total_pages == 100
    assert elapsed < 1, f"{elapsed:.2f}s for the last page"


async def test_the_totals_count_five_thousand_videos(db: None) -> None:
    collection = await _big_collection()
    first = await Download.filter(parent_id=collection.id, media__playlist_index__lte=1_000).values_list(
        "id", flat=True
    )
    await Download.filter(id__in=list(first)).update(status=DownloadStatus.COMPLETED)

    started = time.monotonic()
    counts = counts_of(await CollectionDatabaseRepo().member_rows(collection.id))
    elapsed = time.monotonic() - started

    assert (counts.total, counts.complete, counts.active) == (SIZE, 1_000, 4_000)
    assert elapsed < 1, f"{elapsed:.2f}s for the totals"


async def test_the_list_counts_a_big_collection_quickly(db: None) -> None:
    """A collection's status and bytes are computed from its videos, counted in one query."""
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
    await repo.add_entries(collection, "Youtube", [_entry(n) for n in range(SIZE + 1, SIZE + 51)])
    elapsed = time.monotonic() - started

    assert len(held) == SIZE
    assert await Download.filter(parent_id=collection.id).count() == SIZE + 50
    assert elapsed < 3, f"{elapsed:.2f}s to join"


async def test_the_list_pages_two_thousand_downloads_quickly(db: None) -> None:
    """Built, sorted and paged in Python, so every top-level row is read: this bounds that cost."""
    rows = [Download(status=DownloadStatus.COMPLETED) for _ in range(2_000)]
    await Download.bulk_create(rows)
    await Media.bulk_create(
        [Media(download_id=row.id, title=f"Talk {n}", preset=Preset.P720) for n, row in enumerate(rows)]
    )
    repo = DownloadDatabaseRepo()

    started = time.monotonic()
    items, meta = await repo.list_items(40, 50, None, "title", {})
    summary = await repo.summary()
    elapsed = time.monotonic() - started

    assert meta.total == 2_000 and len(items) == 50
    assert summary.completed == 2_000
    assert elapsed < 1, f"{elapsed:.2f}s for a page and the counts"
