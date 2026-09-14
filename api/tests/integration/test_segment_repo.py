from typing import Any

import pytest
from src.data.db.model import Segment, Task
from src.data.repo import SegmentDatabaseRepo
from src.data.type import Kind, Platform, Preset, TaskStatus

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]

PLAN = [(0, 0, 249), (1, 250, 499), (2, 500, 749), (3, 750, 999)]


async def _task(**overrides: Any) -> Task:
    fields: dict[str, Any] = {
        "source_url": "https://cdn.test/f",
        "platform": Platform.DIRECT,
        "preset": Preset.BEST,
        "kind": Kind.FILE,
        "status": TaskStatus.PENDING,
        "title": "f",
        "filename": "f",
    }
    fields.update(overrides)
    return await Task.create(**fields)


async def test_a_first_reconcile_creates_the_plan_and_reports_fresh(db: None) -> None:
    task = await _task()
    result = await SegmentDatabaseRepo().reconcile(task.id, "file", PLAN)
    assert result.fresh is True
    assert result.watermarks == {0: 0, 1: 0, 2: 0, 3: 0}
    assert await Segment.filter(task_id=task.id).count() == 4


async def test_a_matching_reconcile_returns_the_saved_watermarks(db: None) -> None:
    task = await _task()
    repo = SegmentDatabaseRepo()
    await repo.reconcile(task.id, "file", PLAN)
    await repo.flush(task.id, "file", {0: 100, 2: 40})

    result = await repo.reconcile(task.id, "file", PLAN)
    assert result.fresh is False
    assert result.watermarks == {0: 100, 1: 0, 2: 40, 3: 0}


async def test_a_changed_plan_discards_the_old_rows(db: None) -> None:
    """DOWNLOAD_SEGMENTS changed, or the server reports a different size. The
    old ranges no longer describe what is on disk."""
    task = await _task()
    repo = SegmentDatabaseRepo()
    await repo.reconcile(task.id, "file", PLAN)
    await repo.flush(task.id, "file", {0: 100})

    result = await repo.reconcile(task.id, "file", [(0, 0, 499), (1, 500, 999)])
    assert result.fresh is True
    assert result.watermarks == {0: 0, 1: 0}
    assert await Segment.filter(task_id=task.id).count() == 2


async def test_parts_of_one_task_do_not_collide(db: None) -> None:
    """A YouTube task has two independent segment sets."""
    task = await _task(platform=Platform.YOUTUBE, kind=Kind.VIDEO)
    repo = SegmentDatabaseRepo()
    await repo.reconcile(task.id, "video", PLAN)
    await repo.reconcile(task.id, "audio", PLAN)
    await repo.flush(task.id, "video", {0: 7})

    assert (await repo.reconcile(task.id, "video", PLAN)).watermarks[0] == 7
    assert (await repo.reconcile(task.id, "audio", PLAN)).watermarks[0] == 0


async def test_clear_removes_one_part_or_all_of_them(db: None) -> None:
    task = await _task()
    repo = SegmentDatabaseRepo()
    await repo.reconcile(task.id, "video", PLAN)
    await repo.reconcile(task.id, "audio", PLAN)

    await repo.clear(task.id, "video")
    assert await Segment.filter(task_id=task.id).count() == 4

    await repo.clear(task.id)
    assert await Segment.filter(task_id=task.id).count() == 0
