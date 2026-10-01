"""The one-time SEARCH_INDEXERS import: what it brings in, and what it never touches again."""

import pytest
from src.service.source.seed import import_env_indexers

from ..search.fake_source_repo import FakeSourceRepo
from .fake_flag_repo import FakeFlagRepo

ENV = {"SEARCH_INDEXERS": "prowlarr=http://p.test/1/api,jackett=http://j.test/api", "SEARCH_INDEXER_KEYS": "prowlarr=key-1"}


@pytest.mark.asyncio
async def test_an_import_brings_every_indexer_with_its_key_and_marks_the_flag() -> None:
    repo, flags = FakeSourceRepo(), FakeFlagRepo()

    assert await import_env_indexers(repo, flags, ENV) == 2

    rows = {row.name: row for row in await repo.list_all()}
    assert [(r.kind, r.enabled, r.base_url, r.api_key) for r in rows.values()] == [
        ("torznab", True, "http://p.test/1/api", "key-1"),
        ("torznab", True, "http://j.test/api", None),
    ]
    assert await flags.is_set("search_indexers_imported") is True


@pytest.mark.asyncio
async def test_a_set_flag_is_a_no_op_even_with_the_variable_present() -> None:
    repo, flags = FakeSourceRepo(), FakeFlagRepo()
    await flags.mark("search_indexers_imported")
    created = await repo.create("prowlarr", "torznab", "http://other.test/1/api", None, True)
    assert created.id is not None
    await repo.delete(created.id)

    assert await import_env_indexers(repo, flags, ENV) == 0

    assert await repo.list_all() == []


@pytest.mark.asyncio
async def test_without_the_variable_nothing_happens_and_the_flag_stays_unset() -> None:
    repo, flags = FakeSourceRepo(), FakeFlagRepo()

    assert await import_env_indexers(repo, flags, {}) == 0
    assert await import_env_indexers(repo, flags, {"SEARCH_INDEXERS": "  "}) == 0

    assert await flags.is_set("search_indexers_imported") is False


@pytest.mark.asyncio
async def test_a_name_that_already_exists_is_not_overwritten() -> None:
    repo, flags = FakeSourceRepo(), FakeFlagRepo()
    await repo.create("prowlarr", "torznab", "http://user.test/1/api", "user-key", False)

    assert await import_env_indexers(repo, flags, ENV) == 1

    rows = {row.name: row for row in await repo.list_all()}
    assert (rows["prowlarr"].base_url, rows["prowlarr"].api_key, rows["prowlarr"].enabled) == (
        "http://user.test/1/api",
        "user-key",
        False,
    )


@pytest.mark.asyncio
async def test_a_broken_value_is_logged_and_the_flag_is_still_written() -> None:
    repo, flags = FakeSourceRepo(), FakeFlagRepo()

    assert await import_env_indexers(repo, flags, {"SEARCH_INDEXERS": "not-a-pair"}) == 0

    assert await repo.list_all() == []
    assert await flags.is_set("search_indexers_imported") is True
