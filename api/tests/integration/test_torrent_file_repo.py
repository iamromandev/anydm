import pytest
from src.data.db.model import Task
from src.data.repo import TorrentFileDatabaseRepo
from src.data.type import Kind, Platform, Preset, TaskStatus

pytestmark = pytest.mark.integration


async def _task() -> Task:
    return await Task.create(
        source_url="magnet:?xt=urn:btih:abc",
        platform=Platform.TORRENT,
        preset=Preset.BEST,
        kind=Kind.TORRENT,
        status=TaskStatus.PENDING,
        info_hash="abc",
    )


@pytest.mark.asyncio
async def test_replace_writes_every_file(db: None) -> None:
    task = await _task()
    repo = TorrentFileDatabaseRepo()

    await repo.replace(
        task.id,
        [(0, "video.mkv", 900, True), (1, "readme.txt", 100, False)],
    )

    rows = await repo.list_for(task.id)
    assert [row.index for row in rows] == [0, 1]
    assert rows[0].path == "video.mkv"
    assert rows[0].size_bytes == 900
    assert rows[0].selected is True
    assert rows[1].selected is False


@pytest.mark.asyncio
async def test_replace_is_idempotent(db: None) -> None:
    """Reconciliation re-adds a torrent; its file rows must not double."""
    task = await _task()
    repo = TorrentFileDatabaseRepo()

    await repo.replace(task.id, [(0, "video.mkv", 900, True)])
    await repo.replace(task.id, [(0, "video.mkv", 900, True)])

    assert len(await repo.list_for(task.id)) == 1


@pytest.mark.asyncio
async def test_selected_indexes_are_sorted_and_filtered(db: None) -> None:
    task = await _task()
    repo = TorrentFileDatabaseRepo()

    await repo.replace(
        task.id,
        [(2, "c.mkv", 30, True), (0, "a.mkv", 10, True), (1, "b.txt", 20, False)],
    )

    assert await repo.selected_indexes(task.id) == [0, 2]


@pytest.mark.asyncio
async def test_selected_size_sums_only_the_selection(db: None) -> None:
    task = await _task()
    repo = TorrentFileDatabaseRepo()

    await repo.replace(task.id, [(0, "a.mkv", 10, True), (1, "b.txt", 20, False)])

    assert await repo.selected_size(task.id) == 10


@pytest.mark.asyncio
async def test_flush_progress_writes_by_index(db: None) -> None:
    task = await _task()
    repo = TorrentFileDatabaseRepo()
    await repo.replace(task.id, [(0, "a.mkv", 100, True), (1, "b.mkv", 200, True)])

    await repo.flush_progress(task.id, [40, 80])

    rows = await repo.list_for(task.id)
    assert [row.downloaded_bytes for row in rows] == [40, 80]


@pytest.mark.asyncio
async def test_flush_progress_ignores_a_short_or_long_array(db: None) -> None:
    """The engine's array and our rows can disagree for one tick after a change."""
    task = await _task()
    repo = TorrentFileDatabaseRepo()
    await repo.replace(task.id, [(0, "a.mkv", 100, True), (1, "b.mkv", 200, True)])

    await repo.flush_progress(task.id, [40])
    await repo.flush_progress(task.id, [50, 90, 999])

    rows = await repo.list_for(task.id)
    assert [row.downloaded_bytes for row in rows] == [50, 90]


@pytest.mark.asyncio
async def test_empty_task_reads_cleanly(db: None) -> None:
    task = await _task()
    repo = TorrentFileDatabaseRepo()

    assert await repo.list_for(task.id) == []
    assert await repo.selected_indexes(task.id) == []
    assert await repo.selected_size(task.id) == 0
    await repo.flush_progress(task.id, [1, 2, 3])
