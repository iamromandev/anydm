"""Seeding inserts the registry's constants for what is missing, and never overwrites."""

import pytest
from src.data.repo.search.interface.source import SearchSourceRow
from src.lib.sources.registry import BUILTINS
from src.service.source.seed import seed_missing_sources

from ..search.fake_source_repo import FakeSourceRepo


@pytest.mark.asyncio
async def test_an_empty_table_gets_every_builtin_at_its_default() -> None:
    repo = FakeSourceRepo()

    assert await seed_missing_sources(repo) == 3

    assert {r.name: (r.kind, r.enabled, r.base_url) for r in await repo.list_all()} == {
        b.name: (b.name, True, b.default_url) for b in BUILTINS.values()
    }


@pytest.mark.asyncio
async def test_seeding_twice_changes_nothing_and_an_edited_row_survives() -> None:
    repo = FakeSourceRepo([SearchSourceRow("nyaa", "nyaa", False, "https://mirror.test", None)])

    assert await seed_missing_sources(repo) == 2
    assert await seed_missing_sources(repo) == 0

    stored = {r.name: r for r in await repo.list_all()}
    assert stored["nyaa"] == SearchSourceRow("nyaa", "nyaa", False, "https://mirror.test", None, stored["nyaa"].id)
