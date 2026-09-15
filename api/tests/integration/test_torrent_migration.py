"""The schema this feature needs is actually in the database.

This exists because of a specific, repeated failure: Tortoise's generated
``AddField`` drops ``db_index``, so the column arrives and the index does not,
and nothing notices until a table is large enough for the missing index to
hurt.
"""

import pytest
from src.data.db.model import Task, TorrentFile
from src.data.type import Kind, Platform, Preset, TaskStatus
from tortoise import Tortoise

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
async def test_task_has_the_torrent_columns(db: None) -> None:
    connection = Tortoise.get_connection("default")
    _, rows = await connection.execute_query(
        "SELECT column_name FROM information_schema.columns WHERE table_name = 'task'"
    )
    columns = {row["column_name"] for row in rows}
    assert {"info_hash", "uploaded_bytes", "peers_connected"} <= columns


@pytest.mark.asyncio
async def test_info_hash_is_indexed(db: None) -> None:
    connection = Tortoise.get_connection("default")
    _, rows = await connection.execute_query(
        "SELECT indexname FROM pg_indexes WHERE tablename = 'task'"
    )
    assert "idx_task_info_hash" in {row["indexname"] for row in rows}


@pytest.mark.asyncio
async def test_torrent_file_rows_cascade_with_their_task(db: None) -> None:
    task = await Task.create(
        source_url="magnet:?xt=urn:btih:abc",
        platform=Platform.TORRENT,
        preset=Preset.BEST,
        kind=Kind.TORRENT,
        status=TaskStatus.PENDING,
        info_hash="abc",
    )
    await TorrentFile.create(task=task, index=0, path="video.mkv", size_bytes=900)
    assert await TorrentFile.filter(task_id=task.id).count() == 1

    await task.delete()
    assert await TorrentFile.filter(task_id=task.id).count() == 0
