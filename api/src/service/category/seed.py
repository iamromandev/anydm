"""Give every built-in category a row where it has none. Downloads takes its fixed id."""

import uuid

from src.data.repo.category import CategoryRepo, CategoryRow
from src.data.type import DOWNLOADS_ID, SEEDED_CATEGORIES


async def seed_missing_categories(repo: CategoryRepo) -> int:
    """Insert what is missing; never touch a row that exists. Returns how many were added."""
    return await repo.seed(
        [
            CategoryRow(
                DOWNLOADS_ID if slug == "downloads" else uuid.uuid4(),
                name,
                slug,
                folder,
                position,
                slug == "downloads",
            )
            for position, (name, slug, folder) in enumerate(SEEDED_CATEGORIES)
        ]
    )
