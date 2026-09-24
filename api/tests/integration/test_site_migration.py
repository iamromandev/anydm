"""Migration 0004 left the task table shaped for any site (#56).

CI builds its database from the migrations, so the schema checks here run
against what 0004 created. The data move is exercised by running the
migration's own statements on a row written the way 0003 would have left it.
"""

import importlib

import pytest
from tortoise import Tortoise

pytestmark = pytest.mark.integration

# A module name that starts with a digit cannot be imported with ``import``.
MIGRATION = importlib.import_module("src.data.db.migration.0004_site")


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


@pytest.mark.asyncio
async def test_a_youtube_row_becomes_a_site_row_and_keeps_its_formats(db: None) -> None:
    connection = Tortoise.get_connection("default")
    # Written as 0003 left YouTube rows (platform 'youtube'), after 0004's copy
    # of the itags into the string columns.
    await connection.execute_query(
        "INSERT INTO task (id, created_at, updated_at, source_url, platform, video_id, preset, kind, title, "
        "filename, video_format, audio_format, status, progress, downloaded_bytes, speed_bps, uploaded_bytes, "
        "upload_speed_bps, peers_connected, attempts) VALUES ('44444444-4444-4444-4444-444444444444', now(), now(), "
        "'https://youtu.be/dQw4w9WgXcQ', 'youtube', 'dQw4w9WgXcQ', '1080', 'video', 'Rick', 'Rick_1080p.mp4', "
        "'137', '140', 'paused', 40, 1000, 0, 0, 0, 0, 1)"
    )

    await connection.execute_query(MIGRATION.MOVE_YOUTUBE_ROWS)

    _, rows = await connection.execute_query(
        "SELECT platform, extractor, video_format, audio_format, status, progress FROM task "
        "WHERE id = '44444444-4444-4444-4444-444444444444'"
    )
    assert dict(rows[0]) == {
        "platform": "site",
        "extractor": "Youtube",
        "video_format": "137",
        "audio_format": "140",
        "status": "paused",
        "progress": 40,
    }
