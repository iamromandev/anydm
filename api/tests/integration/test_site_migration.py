"""The task table is shaped for any site (#56).

CI builds its database from the migrations, so this checks what ``0002_task``
creates. The data move that once took YouTube rows to ``site`` went with the
old ``0004_site`` when the migrations were regenerated: a database built
from them has no such rows.
"""

import pytest
from tortoise import Tortoise

pytestmark = pytest.mark.integration


async def _columns() -> dict[str, str]:
    connection = Tortoise.get_connection("default")
    _, rows = await connection.execute_query(
        "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'task'"
    )
    return {row["column_name"]: row["data_type"] for row in rows}


@pytest.mark.asyncio
async def test_format_ids_are_strings_and_the_itag_columns_are_gone(db: None) -> None:
    columns = await _columns()

    assert columns["video_format"] == "character varying"
    assert columns["audio_format"] == "character varying"
    assert columns["extractor"] == "character varying"
    assert "video_itag" not in columns and "audio_itag" not in columns
