import pytest
from tortoise import Tortoise

pytestmark = pytest.mark.integration

#: Where each table lives: the schema is its domain, as in the model folders.
TABLES = {
    "catalog": {"provider", "source"},
    "config": {"preference"},
    "iam": {"user"},
    "play": {"playback_position"},
    "shared": {"tag", "url"},
    "torrent": {"torrent", "torrent_file", "peer", "piece", "tracker"},
    "transfer": {"download", "file", "mirror", "attempt", "segment", "media"},
}


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_every_table_is_in_its_domains_schema() -> None:
    conn = Tortoise.get_connection("default")
    rows = await conn.execute_query_dict(
        "SELECT schemaname, tablename FROM pg_tables "
        "WHERE schemaname IN ('catalog', 'config', 'iam', 'play', 'shared', 'torrent', 'transfer')"
    )
    found: dict[str, set[str]] = {}
    for row in rows:
        found.setdefault(row["schemaname"], set()).add(row["tablename"])

    assert found == TABLES


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_what_keeps_one_thing_one_row_is_in_place() -> None:
    """Identity lives in the catalog now: one Url per address, one Source per way it is used.

    A download is found again by its address (the duplicate check and the
    picker's marks), so these unique indexes are what make that lookup hold.
    """
    conn = Tortoise.get_connection("default")
    unique = [
        row["indexdef"]
        for row in await conn.execute_query_dict(
            "SELECT indexdef FROM pg_indexes WHERE schemaname IN ('shared', 'catalog', 'transfer') "
            "AND indexdef LIKE 'CREATE UNIQUE INDEX%'"
        )
    ]

    def has(table: str, columns: str) -> bool:
        return any(f" {table} USING btree ({columns})" in index for index in unique)

    assert has("shared.url", "normalized_hash")
    assert has("catalog.source", "provider_id, url_id, kind")
    # One Media per download, and one file per index of a download.
    assert has("transfer.media", "download_id")
    assert has("transfer.file", "download_id, index")


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_the_list_view_is_in_place_with_the_columns_the_list_reads() -> None:
    conn = Tortoise.get_connection("default")
    columns = {
        row["column_name"]
        for row in await conn.execute_query_dict(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema = 'transfer' AND table_name = 'list_item'"
        )
    }

    assert columns == {"type", "id", "title", "status", "created_at", "total_size", "downloaded_size", "progress"}
