import asyncio
from datetime import timedelta

import pytest
from src.core.common import now
from src.data.db.model import Task
from src.data.repo import TaskDatabaseRepo
from src.data.type import Kind, Platform, Preset, TaskStatus

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]


async def _pending(title: str = "t") -> Task:
    return await Task.create(
        source_url="https://youtu.be/x",
        platform=Platform.YOUTUBE,
        preset=Preset.BEST,
        kind=Kind.VIDEO,
        status=TaskStatus.PENDING,
        title=title,
        filename=f"{title}.mp4",
    )


async def test_claim_next_returns_and_marks_downloading(db: None) -> None:
    await _pending()
    claimed = await TaskDatabaseRepo().claim_next()
    assert claimed is not None
    assert claimed.status == TaskStatus.DOWNLOADING
    assert claimed.started_at is not None


async def test_claim_next_returns_none_when_empty(db: None) -> None:
    assert await TaskDatabaseRepo().claim_next() is None


async def test_two_concurrent_claims_never_take_the_same_row(db: None) -> None:
    await _pending("only")
    repo = TaskDatabaseRepo()
    first, second = await asyncio.gather(repo.claim_next(), repo.claim_next())
    claimed = [task for task in (first, second) if task is not None]
    assert len(claimed) == 1


async def test_claim_next_is_oldest_first(db: None) -> None:
    old = await _pending("old")
    await _pending("new")
    claimed = await TaskDatabaseRepo().claim_next()
    assert claimed is not None
    assert claimed.id == old.id


async def test_claim_next_skips_a_backed_off_task(db: None) -> None:
    task = await _pending()
    task.next_attempt_at = now() + timedelta(minutes=5)
    await task.save()
    assert await TaskDatabaseRepo().claim_next() is None


async def test_claim_next_takes_a_task_whose_backoff_has_expired(db: None) -> None:
    task = await _pending()
    task.next_attempt_at = now() - timedelta(seconds=1)
    await task.save()
    assert await TaskDatabaseRepo().claim_next() is not None


async def test_recover_orphans_requeues_and_keeps_bytes(db: None) -> None:
    task = await _pending()
    task.status = TaskStatus.DOWNLOADING
    task.downloaded_bytes = 4096
    await task.save()

    recovered = await TaskDatabaseRepo().recover_orphans()

    await task.refresh_from_db()
    assert recovered == 1
    assert task.status == TaskStatus.PENDING
    assert task.downloaded_bytes == 4096


async def test_recover_orphans_leaves_paused_alone(db: None) -> None:
    task = await _pending()
    task.status = TaskStatus.PAUSED
    await task.save()

    assert await TaskDatabaseRepo().recover_orphans() == 0
    await task.refresh_from_db()
    assert task.status == TaskStatus.PAUSED


async def test_flush_progress_writes_and_heartbeats(db: None) -> None:
    task = await _pending()
    await TaskDatabaseRepo().flush_progress(
        task.id, downloaded_bytes=512, total_bytes=1024, progress=50, speed_bps=256, eta_seconds=2
    )
    await task.refresh_from_db()
    assert task.downloaded_bytes == 512
    assert task.progress == 50
    assert task.speed_bps == 256
    assert task.eta_seconds == 2
    assert task.heartbeat_at is not None


async def test_list_page_paginates(db: None) -> None:
    for index in range(5):
        await _pending(f"t{index}")
    tasks, meta = await TaskDatabaseRepo().list_page(page=1, page_size=2)
    assert len(tasks) == 2
    assert meta.total == 5
    assert meta.total_pages == 3


async def test_get_active_by_id_ignores_soft_deleted(db: None) -> None:
    task = await _pending()
    repo = TaskDatabaseRepo()
    assert await repo.get_active_by_id(task.id) is not None
    await task.soft_delete()
    assert await repo.get_active_by_id(task.id) is None


async def test_recover_orphans_leaves_torrents_alone(db: None) -> None:
    """rqbit owns a torrent's transfer, and it survived the restart too."""
    torrent = await Task.create(
        source_url="magnet:?xt=urn:btih:abc",
        platform=Platform.TORRENT,
        preset=Preset.BEST,
        kind=Kind.TORRENT,
        status=TaskStatus.DOWNLOADING,
        info_hash="abc",
    )

    await TaskDatabaseRepo().recover_orphans()

    await torrent.refresh_from_db()
    assert torrent.status == TaskStatus.DOWNLOADING


async def test_claim_next_never_returns_a_torrent(db: None) -> None:
    await Task.create(
        source_url="magnet:?xt=urn:btih:abc",
        platform=Platform.TORRENT,
        preset=Preset.BEST,
        kind=Kind.TORRENT,
        status=TaskStatus.PENDING,
        info_hash="abc",
    )

    assert await TaskDatabaseRepo().claim_next() is None


async def test_torrents_to_watch_returns_live_torrent_rows_only(db: None) -> None:
    watched = await Task.create(
        source_url="magnet:?xt=urn:btih:abc",
        platform=Platform.TORRENT,
        preset=Preset.BEST,
        kind=Kind.TORRENT,
        status=TaskStatus.DOWNLOADING,
        info_hash="abc",
    )
    await Task.create(
        source_url="magnet:?xt=urn:btih:def",
        platform=Platform.TORRENT,
        preset=Preset.BEST,
        kind=Kind.TORRENT,
        status=TaskStatus.CANCELED,
        info_hash="def",
        deleted_at=now(),
    )
    await Task.create(
        source_url="https://example.test/a.bin",
        platform=Platform.DIRECT,
        preset=Preset.BEST,
        kind=Kind.FILE,
        status=TaskStatus.DOWNLOADING,
    )

    rows = await TaskDatabaseRepo().torrents_to_watch()

    assert [row.id for row in rows] == [watched.id]


async def _task(status: TaskStatus, title: str) -> Task:
    return await Task.create(
        source_url="https://youtu.be/x",
        platform=Platform.YOUTUBE,
        preset=Preset.BEST,
        kind=Kind.VIDEO,
        status=status,
        title=title,
        filename=f"{title}.mp4",
    )


async def test_list_page_can_be_narrowed_to_a_set_of_statuses(db: None) -> None:
    await _task(TaskStatus.DOWNLOADING, "a")
    await _task(TaskStatus.COMPLETE, "b")
    await _task(TaskStatus.SEEDING, "c")

    rows, meta = await TaskDatabaseRepo().list_page(
        page=1, page_size=10, statuses=[TaskStatus.COMPLETE, TaskStatus.SEEDING]
    )

    assert {row.title for row in rows} == {"b", "c"}
    # The total describes the filtered set, or "load more" would never end.
    assert meta.total == 2


async def test_list_page_without_statuses_returns_everything(db: None) -> None:
    await _task(TaskStatus.DOWNLOADING, "a")
    await _task(TaskStatus.COMPLETE, "b")

    rows, meta = await TaskDatabaseRepo().list_page(page=1, page_size=10, statuses=None)

    assert meta.total == 2
    assert len(rows) == 2


async def test_a_second_page_carries_on_where_the_first_stopped(db: None) -> None:
    for index in range(5):
        await _task(TaskStatus.COMPLETE, f"t{index}")

    repo = TaskDatabaseRepo()
    first, meta = await repo.list_page(page=1, page_size=2, statuses=None)
    second, _ = await repo.list_page(page=2, page_size=2, statuses=None)

    assert meta.total == 5
    assert meta.total_pages == 3
    assert {row.id for row in first}.isdisjoint({row.id for row in second})


async def test_summary_counts_each_group_the_sidebar_shows(db: None) -> None:
    await _task(TaskStatus.PENDING, "a")
    await _task(TaskStatus.DOWNLOADING, "b")
    await _task(TaskStatus.MUXING, "c")
    await _task(TaskStatus.SEEDING, "d")
    await _task(TaskStatus.COMPLETE, "e")
    await _task(TaskStatus.FAILED, "f")

    summary = await TaskDatabaseRepo().summary()

    assert summary.all == 6
    # "Active" means still on its way to a file, which is what the filter says.
    assert summary.downloading == 3
    assert summary.seeding == 1
    assert summary.completed == 1


async def test_summary_ignores_soft_deleted_rows(db: None) -> None:
    kept = await _task(TaskStatus.COMPLETE, "kept")
    gone = await _task(TaskStatus.COMPLETE, "gone")
    gone.deleted_at = now()
    await gone.save(update_fields=["deleted_at"])

    summary = await TaskDatabaseRepo().summary()

    assert summary.all == 1
    assert summary.completed == 1
    assert kept.deleted_at is None


async def _sized(title: str, total: int | None, progress: int = 0) -> Task:
    return await Task.create(
        source_url="https://youtu.be/x",
        platform=Platform.YOUTUBE,
        preset=Preset.BEST,
        kind=Kind.VIDEO,
        status=TaskStatus.COMPLETE,
        title=title,
        filename=f"{title}.mp4",
        total_bytes=total,
        progress=progress,
    )


async def test_the_default_order_is_newest_first(db: None) -> None:
    await _sized("first", 1)
    await asyncio.sleep(0.01)
    await _sized("second", 2)

    rows, _ = await TaskDatabaseRepo().list_page(page=1, page_size=10)

    assert [row.title for row in rows] == ["second", "first"]


async def test_sorting_by_title_is_alphabetical(db: None) -> None:
    for title in ("charlie", "alpha", "bravo"):
        await _sized(title, 1)

    rows, _ = await TaskDatabaseRepo().list_page(page=1, page_size=10, sort="title")

    assert [row.title for row in rows] == ["alpha", "bravo", "charlie"]


async def test_sorting_by_size_puts_an_unknown_size_at_the_small_end(
    db: None,
) -> None:
    """A direct download whose server sent no length has `total_bytes` NULL.

    Postgres sorts NULL first on a descending order, which would put the one
    task whose size nobody knows at the top of "largest first".
    """
    await _sized("small", 10)
    await _sized("huge", 9_000)
    await _sized("unknown", None)

    rows, _ = await TaskDatabaseRepo().list_page(
        page=1, page_size=10, sort="-total_bytes"
    )

    assert [row.title for row in rows] == ["huge", "small", "unknown"]


async def test_sorting_carries_across_pages(db: None) -> None:
    for index, title in enumerate(["d", "a", "c", "b"]):
        await _sized(title, index)

    repo = TaskDatabaseRepo()
    first, _ = await repo.list_page(page=1, page_size=2, sort="title")
    second, _ = await repo.list_page(page=2, page_size=2, sort="title")

    assert [row.title for row in first] == ["a", "b"]
    assert [row.title for row in second] == ["c", "d"]
