import pytest
from tortoise import Tortoise

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_hand_written_sql_is_in_place() -> None:
    conn = Tortoise.get_connection("default")
    indexes = {
        row["indexname"]: row["indexdef"]
        for row in await conn.execute_query_dict(
            "SELECT indexname, indexdef FROM pg_indexes WHERE tablename IN ('collection', 'download', 'site_detail')"
        )
    }
    assert "WHERE (deleted_at IS NULL)" in indexes["uniq_collection_listing"]
    assert "USING hash (source_url)" in indexes["idx_download_source_url"]
    # CreateModel kept db_index (the AddField trap does not apply to a new table).
    assert any("(video_id)" in definition for definition in indexes.values())
    assert await conn.execute_query_dict("SELECT 1 FROM pg_views WHERE viewname = 'list_item'")
