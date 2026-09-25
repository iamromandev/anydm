"""Where each download was left, in Postgres (#96)."""

import pytest
from src.data.db.model import PlaybackPosition, Task
from src.data.repo import PositionDatabaseRepo
from src.data.type import Kind, Platform, Preset, TaskStatus

pytestmark = pytest.mark.integration


async def _task() -> Task:
    return await Task.create(
        source_url="magnet:?xt=urn:btih:abc",
        platform=Platform.TORRENT,
        preset=Preset.BEST,
        kind=Kind.TORRENT,
        status=TaskStatus.COMPLETE,
        info_hash="abc",
    )


@pytest.mark.asyncio
async def test_a_position_is_saved_and_then_moved_in_place(db: None) -> None:
    task = await _task()
    repo = PositionDatabaseRepo()

    await repo.save(task.id, 2, position_seconds=61.5, duration_seconds=1300.0, watched=False)
    await repo.save(task.id, 2, position_seconds=120.0, duration_seconds=1300.0, watched=False)

    (row,) = (await repo.list_for_tasks([task.id]))[task.id]
    assert (row.file_index, row.position_seconds, row.duration_seconds, row.watched) == (2, 120.0, 1300.0, False)
    assert await PlaybackPosition.filter(task_id=task.id).count() == 1


@pytest.mark.asyncio
async def test_a_page_of_tasks_gets_its_positions_in_one_query(db: None) -> None:
    first, second, none = await _task(), await _task(), await _task()
    repo = PositionDatabaseRepo()
    await repo.save(first.id, 1, position_seconds=10.0, duration_seconds=100.0, watched=False)
    await repo.save(first.id, 0, position_seconds=0.0, duration_seconds=100.0, watched=True)
    await repo.save(second.id, 0, position_seconds=5.0, duration_seconds=50.0, watched=False)

    by_task = await repo.list_for_tasks([first.id, second.id, none.id])

    assert [(r.file_index, r.watched) for r in by_task[first.id]] == [(0, True), (1, False)]
    assert [r.position_seconds for r in by_task[second.id]] == [5.0]
    assert by_task[none.id] == []
    assert await repo.list_for_tasks([]) == {}


@pytest.mark.asyncio
async def test_removing_a_task_removes_its_positions(db: None) -> None:
    task = await _task()
    await PositionDatabaseRepo().save(task.id, 0, position_seconds=1.0, duration_seconds=2.0, watched=False)

    await task.delete()

    assert await PlaybackPosition.all().count() == 0
