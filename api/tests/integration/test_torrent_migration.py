"""The schema this feature needs is actually in the database.

This exists because of two specific, repeated failures:

* Tortoise's generated ``AddField`` drops ``db_index``, so the column arrives
  and the index does not, and nothing notices until a table is large enough
  for the missing index to hurt.
* ``ops.RenameModel`` on a model that owns a foreign key only reloads the
  renamed model itself, not the model its field points at, and re-registering
  the backward relation without ever clearing the old one raises
  ``backward relation "..." duplicates``. ``0004_torrent_file_to_file`` works
  around it; this is what proves the workaround actually lands correctly.
"""

import pytest
from src.data.db.model import File, Task
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
    assert {"info_hash", "uploaded_bytes", "upload_speed_bps", "peers_connected"} <= columns


@pytest.mark.asyncio
async def test_info_hash_is_indexed(db: None) -> None:
    connection = Tortoise.get_connection("default")
    _, rows = await connection.execute_query(
        "SELECT indexname FROM pg_indexes WHERE tablename = 'task'"
    )
    assert "idx_task_info_hash" in {row["indexname"] for row in rows}


@pytest.mark.asyncio
async def test_the_torrent_file_table_was_renamed_to_file(db: None) -> None:
    connection = Tortoise.get_connection("default")
    _, rows = await connection.execute_query(
        "SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
    )
    tables = {row["tablename"] for row in rows}
    assert "file" in tables
    assert "torrent_file" not in tables


@pytest.mark.asyncio
async def test_file_rows_cascade_with_their_task(db: None) -> None:
    task = await Task.create(
        source_url="magnet:?xt=urn:btih:abc",
        platform=Platform.TORRENT,
        preset=Preset.BEST,
        kind=Kind.TORRENT,
        status=TaskStatus.PENDING,
        info_hash="abc",
    )
    await File.create(task=task, index=0, path="video.mkv", size_bytes=900)
    assert await File.filter(task_id=task.id).count() == 1

    await task.delete()
    assert await File.filter(task_id=task.id).count() == 0
