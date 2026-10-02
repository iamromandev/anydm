import pytest
from src.data.repo.download.interface.category import CategoryRow
from src.data.repo.download.interface.queue import QueueRow
from src.service.download.seed import CATEGORIES, seed_organization


class FakeCategories:
    def __init__(self, have: set[str]) -> None:
        self.have = have
        self.inserted: list[CategoryRow] = []

    async def insert_missing(self, rows: list[CategoryRow]) -> int:
        fresh = [row for row in rows if row.name not in self.have]
        self.inserted += fresh
        return len(fresh)


class FakeQueues:
    def __init__(self) -> None:
        self.inserted: list[QueueRow] = []

    async def insert_missing(self, rows: list[QueueRow]) -> int:
        self.inserted += rows
        return len(rows)


def test_six_categories_and_no_extension_twice() -> None:
    assert [c.name for c in CATEGORIES] == ["Video", "Music", "Software", "Documents", "Compressed", "Other"]
    every = [ext for c in CATEGORIES for ext in c.extensions]
    assert len(every) == len(set(every))
    assert CATEGORIES[-1].extensions == ()


@pytest.mark.asyncio
async def test_seed_adds_only_what_is_missing_and_main_takes_the_worker_count() -> None:
    categories, queues = FakeCategories(have={"Video"}), FakeQueues()
    assert await seed_organization(categories, queues, workers=3) == (5, 1)  # ty: ignore[invalid-argument-type]
    assert "Video" not in [row.name for row in categories.inserted]
    assert queues.inserted == [QueueRow("Main", 3, 0)]
