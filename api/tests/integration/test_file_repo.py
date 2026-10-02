import pytest
from src.data.repo import FileDatabaseRepo, PositionDatabaseRepo

from tests.integration.rows import a_download

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]


@pytest.mark.asyncio
async def test_replace_is_idempotent_and_ordered() -> None:
    repo, row = FileDatabaseRepo(), await a_download()
    files = [(1, "b.mkv", 20, False), (0, "a.mkv", 10, True)]
    await repo.replace(row.id, files)
    await repo.replace(row.id, files)
    assert [(f.index, f.path) for f in await repo.list_for(row.id)] == [(0, "a.mkv"), (1, "b.mkv")]
    assert await repo.selected_indexes(row.id) == [0]


@pytest.mark.asyncio
async def test_single_file_is_named_then_finished() -> None:
    repo, row = FileDatabaseRepo(), await a_download()
    await repo.set_single(row.id, path="007_", mime_type=None)
    await repo.set_single(row.id, path="007_Talk.mp4", mime_type="video/mp4")
    await repo.finish_single(row.id, path="007_Talk.mp4", size_bytes=42)
    single = await repo.single(row.id)
    assert single is not None
    assert (single.path, single.mime_type, single.size_bytes, single.downloaded_bytes) == (
        "007_Talk.mp4",
        "video/mp4",
        42,
        42,
    )


@pytest.mark.asyncio
async def test_progress_by_index_and_batch_listing() -> None:
    repo, row, other = FileDatabaseRepo(), await a_download(), await a_download()
    await repo.replace(row.id, [(0, "a", 10, True), (1, "b", 10, True)])
    await repo.flush_progress(row.id, [5, 7, 99])
    listed = await repo.list_for_downloads([row.id, other.id])
    assert [f.downloaded_bytes for f in listed[row.id]] == [5, 7]
    assert listed[other.id] == []


@pytest.mark.asyncio
async def test_a_position_per_file() -> None:
    files, positions, row = FileDatabaseRepo(), PositionDatabaseRepo(), await a_download()
    single = await files.set_single(row.id, path="a.mp4", mime_type="video/mp4")
    await positions.save(single.id, position_seconds=5, duration_seconds=60, watched=False)
    await positions.save(single.id, position_seconds=9, duration_seconds=60, watched=False)
    assert (await positions.by_files([single.id]))[single.id].position_seconds == 9
