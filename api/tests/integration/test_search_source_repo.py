"""The search_source repository against a real Postgres. Point DB_NAME at a scratch database, never the dev one."""

from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from src.data.db import DB_CONFIG
from src.data.db.model import SearchSource
from src.data.repo import SearchSourceDatabaseRepo
from src.data.repo.search.interface.source import SearchSourceRow
from tortoise import Tortoise

pytestmark = pytest.mark.integration


@pytest_asyncio.fixture
async def sources() -> AsyncIterator[SearchSourceDatabaseRepo]:
    """This file's own fixture: it snapshots the table, empties it, and puts every row back."""
    await Tortoise.init(config=DB_CONFIG)
    saved = [SearchSourceRow(s.name, s.enabled, s.base_url) for s in await SearchSource.all()]
    await SearchSource.all().delete()
    yield SearchSourceDatabaseRepo()
    await SearchSource.all().delete()
    if saved:
        await SearchSource.bulk_create([SearchSource(name=r.name, enabled=r.enabled, base_url=r.base_url) for r in saved])
    await Tortoise.close_connections()


@pytest.mark.asyncio
async def test_insert_missing_adds_only_what_is_missing(sources: SearchSourceDatabaseRepo) -> None:
    assert await sources.insert_missing([SearchSourceRow("apibay", True, "https://a.test"), SearchSourceRow("nyaa", True, "https://n.test")]) == 2
    assert await sources.insert_missing([SearchSourceRow("nyaa", False, "https://other.test"), SearchSourceRow("eztv", True, "https://e.test")]) == 1

    rows = {r.name: r for r in await sources.list_all()}
    assert set(rows) == {"apibay", "nyaa", "eztv"}
    assert rows["nyaa"] == SearchSourceRow("nyaa", True, "https://n.test")


@pytest.mark.asyncio
async def test_insert_missing_never_overwrites_an_edited_row(sources: SearchSourceDatabaseRepo) -> None:
    await sources.insert_missing([SearchSourceRow("apibay", True, "https://a.test")])
    await sources.update("apibay", enabled=False, base_url="https://mirror.test")

    assert await sources.insert_missing([SearchSourceRow("apibay", True, "https://a.test")]) == 0
    assert await sources.get("apibay") == SearchSourceRow("apibay", False, "https://mirror.test")


@pytest.mark.asyncio
async def test_update_changes_what_is_given_and_reports_a_missing_row(sources: SearchSourceDatabaseRepo) -> None:
    await sources.insert_missing([SearchSourceRow("apibay", True, "https://a.test")])

    assert await sources.update("apibay", enabled=False, base_url=None) == SearchSourceRow("apibay", False, "https://a.test")
    assert await sources.update("apibay", enabled=None, base_url="https://b.test") == SearchSourceRow("apibay", False, "https://b.test")
    assert await sources.update("nope", enabled=True, base_url=None) is None
    assert await sources.get("nope") is None


@pytest.mark.asyncio
async def test_the_list_is_oldest_first(sources: SearchSourceDatabaseRepo) -> None:
    await sources.insert_missing([SearchSourceRow("apibay", True, "a")])
    await sources.insert_missing([SearchSourceRow("nyaa", True, "n")])

    assert [r.name for r in await sources.list_all()] == ["apibay", "nyaa"]
