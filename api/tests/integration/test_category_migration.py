"""After 0009 every download is in a category, and the list still answers."""

import pytest
from tortoise import Tortoise

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_the_seeded_categories_are_there_in_order_with_downloads_built_in() -> None:
    rows = await Tortoise.get_connection("default").execute_query_dict(
        "SELECT slug, folder, builtin FROM transfer.category ORDER BY position"
    )
    assert rows[0] == {"slug": "downloads", "folder": "", "builtin": True}
    assert len(rows) == 15 and not any(row["builtin"] for row in rows[1:])


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_the_list_view_exposes_category_id() -> None:
    columns = await Tortoise.get_connection("default").execute_query_dict(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_schema = 'transfer' AND table_name = 'list_item'"
    )
    assert "category_id" in {row["column_name"] for row in columns}


@pytest.mark.asyncio
@pytest.mark.usefixtures("db")
async def test_download_category_id_is_indexed_and_required() -> None:
    conn = Tortoise.get_connection("default")
    indexes = await conn.execute_query_dict(
        "SELECT indexname FROM pg_indexes WHERE schemaname = 'transfer' AND tablename = 'download'"
    )
    assert "idx_download_categor_040425" in {row["indexname"] for row in indexes}
    nullable = await conn.execute_query_dict(
        "SELECT is_nullable FROM information_schema.columns "
        "WHERE table_schema = 'transfer' AND table_name = 'download' AND column_name = 'category_id'"
    )
    assert nullable == [{"is_nullable": "NO"}]
