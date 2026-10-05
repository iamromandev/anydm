import pytest
from tortoise import Tortoise

pytestmark = pytest.mark.integration

#: Where each table lives: the schema is its domain, as in the model folders.
TABLES = {
    "catalog": {"provider", "source"},
    "config": {"preference"},
    "iam": {"user"},
    "shared": {"tag", "url"},
    "torrent": {"torrent", "torrent_file", "peer", "piece", "tracker"},
    "transfer": {"download", "file", "mirror", "attempt", "segment"},
}


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_every_table_is_in_its_domains_schema() -> None:
    conn = Tortoise.get_connection("default")
    rows = await conn.execute_query_dict(
        "SELECT schemaname, tablename FROM pg_tables "
        "WHERE schemaname IN ('catalog', 'config', 'iam', 'shared', 'torrent', 'transfer')"
    )
    found: dict[str, set[str]] = {}
    for row in rows:
        found.setdefault(row["schemaname"], set()).add(row["tablename"])

    assert found == TABLES


@pytest.mark.skip(reason="the tables these indexes sit on come back in #462 and #463")
@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_hand_written_sql_is_in_place() -> None:
    conn = Tortoise.get_connection("default")
    indexes = {
        row["indexname"]: row["indexdef"]
        for row in await conn.execute_query_dict(
            "SELECT indexname, indexdef FROM pg_indexes "
            "WHERE tablename IN ('download', 'site_detail', 'queue')"
        )
    }
    # One container per playlist or channel tab: adding it again joins the one that is there.
    assert "playlist" in indexes["uniq_download_container"] and "deleted_at IS NULL" in indexes["uniq_download_container"]
    # What a download is, looked up by provider and id for the duplicate check and the picker's marks.
    assert "(provider, ref_id)" in indexes["idx_download_identity"]
    # Exactly one queue is the default, which a plain unique cannot say.
    assert "WHERE" in indexes["uniq_queue_default"] and "is_default" in indexes["uniq_queue_default"]
    assert await conn.execute_query_dict(
        "SELECT 1 FROM pg_views WHERE schemaname = 'transfer' AND viewname = 'list_item'"
    )
