"""Seeding inserts the built-in categories that are missing, and never overwrites."""

import pytest
from src.data.type import DOWNLOADS_ID, SEEDED_CATEGORIES
from src.service.category.seed import seed_missing_categories

from .fake_category_repo import FakeCategoryRepo


@pytest.mark.asyncio
async def test_an_empty_table_gets_every_builtin_in_order_with_downloads_at_its_fixed_id() -> None:
    repo = FakeCategoryRepo(seeded=False)

    assert await seed_missing_categories(repo) == len(SEEDED_CATEGORIES)

    rows = await repo.list_all()
    assert [(r.name, r.slug, r.folder) for r in rows] == list(SEEDED_CATEGORIES)
    assert [r.position for r in rows] == list(range(len(SEEDED_CATEGORIES)))
    assert (rows[0].id, rows[0].builtin) == (DOWNLOADS_ID, True)
    assert not any(r.builtin for r in rows[1:])


@pytest.mark.asyncio
async def test_seeding_twice_changes_nothing_and_an_edited_row_survives() -> None:
    repo = FakeCategoryRepo(seeded=False)
    await seed_missing_categories(repo)
    movies = repo.named("movies")
    await repo.update(movies.id, name="Films", folder="cinema")

    assert await seed_missing_categories(repo) == 0
    assert (repo.rows[movies.id].name, repo.rows[movies.id].folder) == ("Films", "cinema")


@pytest.mark.asyncio
async def test_a_deleted_builtin_comes_back() -> None:
    repo = FakeCategoryRepo()
    await repo.delete(repo.named("games").id)

    assert await seed_missing_categories(repo) == 1
    assert repo.named("games").folder == "games"
