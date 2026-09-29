"""Seeding inserts the registry's constants for what is missing, and never overwrites."""

import pytest
from src.data.repo.search.interface.source import SearchSourceRow
from src.lib.sources.registry import BUILTINS
from src.service.search.seed import seed_missing_sources

from .fake_source_repo import FakeSourceRepo


@pytest.mark.asyncio
async def test_an_empty_table_gets_every_builtin_at_its_default() -> None:
    repo = FakeSourceRepo()

    assert await seed_missing_sources(repo) == 3

    assert {r.name: (r.enabled, r.base_url) for r in await repo.list_all()} == {b.name: (True, b.default_url) for b in BUILTINS.values()}


@pytest.mark.asyncio
async def test_seeding_twice_changes_nothing_and_an_edited_row_survives() -> None:
    repo = FakeSourceRepo([SearchSourceRow("nyaa", False, "https://mirror.test")])

    assert await seed_missing_sources(repo) == 2
    assert await seed_missing_sources(repo) == 0

    assert await repo.get("nyaa") == SearchSourceRow("nyaa", False, "https://mirror.test")
