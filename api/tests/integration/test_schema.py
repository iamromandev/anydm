import pytest
from tortoise import Tortoise

pytestmark = pytest.mark.integration

#: Where each table lives: the schema is its domain, as in the model folders.
TABLES = {
    "iam": {"user", "user_setting", "session", "share"},
    "transfer": {
        "download",
        "download_file",
        "segment",
        "mirror",
        "playback_position",
        "site_detail",
        "torrent_detail",
    },
    "organize": {"collection", "folder", "queue"},
    "shared": {"source", "tag"},
}


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_every_table_is_in_its_domains_schema() -> None:
    conn = Tortoise.get_connection("default")
    rows = await conn.execute_query_dict(
        "SELECT schemaname, tablename FROM pg_tables WHERE schemaname IN ('iam', 'transfer', 'organize', 'shared')"
    )
    found: dict[str, set[str]] = {}
    for row in rows:
        found.setdefault(row["schemaname"], set()).add(row["tablename"])

    assert found == TABLES


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_hand_written_sql_is_in_place() -> None:
    conn = Tortoise.get_connection("default")
    indexes = {
        row["indexname"]: row["indexdef"]
        for row in await conn.execute_query_dict(
            "SELECT indexname, indexdef FROM pg_indexes "
            "WHERE tablename IN ('collection', 'download', 'site_detail', 'share')"
        )
    }
    assert "WHERE (deleted_at IS NULL)" in indexes["uniq_collection_listing"]
    assert "USING hash (source_url)" in indexes["idx_download_source_url"]
    # CreateModel applies no partial uniques, so the rule that stops two "everyone" shares is written by hand.
    assert "WHERE (user_id IS NULL)" in indexes["uniq_share_everyone"]
    # CreateModel kept db_index (the AddField trap does not apply to a new table).
    assert any("(video_id)" in definition for definition in indexes.values())
    assert await conn.execute_query_dict(
        "SELECT 1 FROM pg_views WHERE schemaname = 'transfer' AND viewname = 'list_item'"
    )
