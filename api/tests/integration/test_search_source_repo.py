"""The search-source repository against a real Postgres. Point DB_NAME at a scratch database, never the dev one.

A search source is a ``catalog.Provider`` with a parser; the fixture touches only those rows.
"""

import uuid
from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from src.data.db import DB_CONFIG
from src.data.db.model import Provider
from src.data.repo import SourceDatabaseRepo
from src.data.repo.search.interface.source import SourceRow
from tortoise import Tortoise

pytestmark = pytest.mark.integration


@pytest_asyncio.fixture
async def sources() -> AsyncIterator[SourceDatabaseRepo]:
    """This file's own fixture: it snapshots the search sources, empties them, and puts every one back."""
    await Tortoise.init(config=DB_CONFIG)
    repo = SourceDatabaseRepo()
    saved = await repo.list_all()
    await Provider.filter(parser__isnull=False).delete()
    yield repo
    await Provider.filter(parser__isnull=False).delete()
    if saved:
        await repo.insert_missing(saved)
    await Tortoise.close_connections()


@pytest.mark.asyncio
async def test_create_then_get_round_trips_every_field(sources: SourceDatabaseRepo) -> None:
    created = await sources.create("prowlarr", "torznab", "http://p.test/1/api", api_key="key-1", enabled=True)
    source_id = created.id

    assert source_id is not None
    assert await sources.get(source_id) == created


@pytest.mark.asyncio
async def test_update_changes_what_is_given_and_reports_a_missing_row(sources: SourceDatabaseRepo) -> None:
    created = await sources.create("prowlarr", "torznab", "http://p.test/1/api", api_key="key-1", enabled=True)
    source_id = created.id
    assert source_id is not None

    assert await sources.update(source_id, enabled=False, base_url=None) == SourceRow(
        "prowlarr", "torznab", False, "http://p.test/1/api", "key-1", source_id
    )
    assert await sources.update(source_id, enabled=None, base_url="http://q.test/1/api") == SourceRow(
        "prowlarr", "torznab", False, "http://q.test/1/api", "key-1", source_id
    )


@pytest.mark.asyncio
async def test_clearing_the_key_needs_saying_so(sources: SourceDatabaseRepo) -> None:
    created = await sources.create("prowlarr", "torznab", "http://p.test/1/api", api_key="key-1", enabled=True)
    source_id = created.id
    assert source_id is not None

    kept = await sources.update(source_id, enabled=None, base_url=None, api_key=None)
    assert kept is not None and kept.api_key == "key-1"

    cleared = await sources.update(source_id, enabled=None, base_url=None, api_key=None, clear_api_key=True)
    assert cleared is not None and cleared.api_key is None


@pytest.mark.asyncio
async def test_delete_removes_and_a_second_delete_is_false(sources: SourceDatabaseRepo) -> None:
    created = await sources.create("prowlarr", "torznab", "http://p.test/1/api", api_key=None, enabled=True)
    source_id = created.id
    assert source_id is not None

    assert await sources.delete(source_id) is True
    assert await sources.get(source_id) is None
    assert await sources.delete(source_id) is False


@pytest.mark.asyncio
async def test_get_on_a_random_id_is_none(sources: SourceDatabaseRepo) -> None:
    assert await sources.get(uuid.uuid4()) is None
    assert await sources.update(uuid.uuid4(), enabled=False, base_url=None) is None


@pytest.mark.asyncio
async def test_insert_missing_never_overwrites_an_edited_row(sources: SourceDatabaseRepo) -> None:
    await sources.insert_missing([SourceRow("nyaa", "nyaa", True, "https://n.test", None)])
    listed = await sources.list_all()
    assert len(listed) == 1
    source_id = listed[0].id
    assert source_id is not None
    await sources.update(source_id, enabled=False, base_url="https://mirror.test")

    assert await sources.insert_missing([SourceRow("nyaa", "nyaa", True, "https://n.test", None)]) == 0
    assert await sources.get(source_id) == SourceRow("nyaa", "nyaa", False, "https://mirror.test", None, source_id)


@pytest.mark.asyncio
async def test_a_search_source_is_a_provider_with_a_parser(sources: SourceDatabaseRepo) -> None:
    created = await sources.create("prowlarr", "torznab", "http://p.test/1/api", api_key="key-1", enabled=False)

    provider = await Provider.get(id=created.id).select_related("base_url")
    assert (provider.slug, provider.parser, provider.api_key) == ("prowlarr", "torznab", "key-1")
    assert provider.status == "inactive"
    assert provider.base_url is not None and provider.base_url.value == "http://p.test/1/api"


@pytest.mark.asyncio
async def test_a_provider_without_a_parser_is_not_a_search_source(sources: SourceDatabaseRepo) -> None:
    other = await Provider.create(name="http", slug="http")
    try:
        assert await sources.list_all() == []
        assert await sources.get(other.id) is None
        assert await sources.delete(other.id) is False
    finally:
        await other.delete()
