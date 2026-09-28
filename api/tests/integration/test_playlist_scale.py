"""Groups at the size a channel archive reaches (part 3): 5,000 videos.

The time bounds are ceilings that catch a missing index or a per-row loop,
not benchmarks.
"""

import time
from typing import Any

import pytest
from src.data.db.model import Task
from src.data.repo import TaskDatabaseRepo
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.service.download.group_totals import counts_of

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]

SIZE = 5_000

GROUP: dict[str, Any] = {
    "source_url": "https://www.youtube.com/@TED/videos",
    "platform": Platform.SITE,
    "preset": Preset.P720,
    "kind": Kind.PLAYLIST,
    "status": TaskStatus.PENDING,
    "title": "TED · Videos",
    "extractor": "YoutubeTab",
    "video_id": "UCAuUUnT6oDeKwE6v1NGQxug",
}


def _entry(n: int) -> dict[str, Any]:
    return {
        "source_url": f"https://www.youtube.com/watch?v=v{n:05d}",
        "platform": Platform.SITE,
        "preset": Preset.P720,
        "kind": Kind.VIDEO,
        "status": TaskStatus.PENDING,
        "title": f"Talk {n}",
        "position": n,
        "extractor": "Youtube",
        "video_id": f"v{n:05d}",
    }


async def _big_group() -> Task:
    return await TaskDatabaseRepo().create_group(GROUP, [_entry(n) for n in range(1, SIZE + 1)])


async def test_adding_five_thousand_videos_is_one_quick_insert(db: None) -> None:
    started = time.monotonic()
    group = await _big_group()
    elapsed = time.monotonic() - started

    assert await Task.filter(parent_id=group.id).count() == SIZE
    assert elapsed < 10, f"{elapsed:.1f}s for {SIZE} videos"


async def test_claim_takes_the_group_in_order_without_scanning_slowly(db: None) -> None:
    await _big_group()
    repo = TaskDatabaseRepo()

    started = time.monotonic()
    claimed = [await repo.claim_next() for _ in range(3)]
    elapsed = time.monotonic() - started

    assert [task and task.position for task in claimed] == [1, 2, 3]
    assert elapsed < 2, f"{elapsed:.2f}s for three claims"


async def test_the_last_page_of_entries_is_as_quick_as_the_first(db: None) -> None:
    group = await _big_group()
    repo = TaskDatabaseRepo()

    started = time.monotonic()
    rows, meta = await repo.entries_page(group.id, page=100, page_size=50)
    elapsed = time.monotonic() - started

    assert [rows[0].position, rows[-1].position] == [4_951, 5_000]
    assert meta.total_pages == 100
    assert elapsed < 1, f"{elapsed:.2f}s for the last page"


async def test_the_totals_count_five_thousand_videos(db: None) -> None:
    group = await _big_group()
    await Task.filter(parent_id=group.id, position__lte=1_000).update(status=TaskStatus.COMPLETE)

    started = time.monotonic()
    counts = counts_of(await TaskDatabaseRepo().entry_statuses(group.id))
    elapsed = time.monotonic() - started

    assert (counts.total, counts.complete, counts.active) == (SIZE, 1_000, 4_000)
    assert elapsed < 1, f"{elapsed:.2f}s for the totals"


async def test_joining_a_big_group_is_quick(db: None) -> None:
    group = await _big_group()
    repo = TaskDatabaseRepo()

    started = time.monotonic()
    held = await repo.held_entries(group.id)
    await repo.add_entries(group, [_entry(n) for n in range(SIZE + 1, SIZE + 51)])
    elapsed = time.monotonic() - started

    assert len(held) == SIZE
    assert await Task.filter(parent_id=group.id).count() == SIZE + 50
    assert elapsed < 3, f"{elapsed:.2f}s to join"
