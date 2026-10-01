"""``0002_task``: a group's columns, and the indexes Tortoise doesn't generate."""

import pytest
from src.data.db.model import Task
from src.data.type import Kind, Platform, Preset, TaskStatus
from tortoise import Tortoise

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]


async def test_the_parent_indexes_exist(db: None) -> None:
    conn = Tortoise.get_connection("default")
    rows = await conn.execute_query_dict("SELECT indexname FROM pg_indexes WHERE tablename = 'task'")
    names = {row["indexname"] for row in rows}
    assert {"idx_task_parent", "idx_task_parent_position"} <= names


async def test_a_video_belongs_to_its_group(db: None) -> None:
    group = await Task.create(
        source_url="https://y.test/list",
        platform=Platform.SITE,
        preset=Preset.BEST,
        kind=Kind.PLAYLIST,
        status=TaskStatus.PENDING,
        title="A list",
    )
    video = await Task.create(
        source_url="https://y.test/v1",
        platform=Platform.SITE,
        preset=Preset.BEST,
        kind=Kind.VIDEO,
        status=TaskStatus.PENDING,
        parent=group,
        position=1,
    )
    fetched = await Task.get(id=video.id)
    parent = await fetched.parent
    assert parent is not None
    assert (parent.id, fetched.position) == (group.id, 1)
