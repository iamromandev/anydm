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
