import pytest
from src.data.repo.download.interface.queue import QueueRow
from src.service.download.seed import seed_organization


class FakeQueues:
    def __init__(self) -> None:
        self.inserted: list[QueueRow] = []

    async def insert_missing(self, rows: list[QueueRow]) -> int:
        self.inserted += rows
        return len(rows)


@pytest.mark.asyncio
async def test_seed_adds_main_with_the_worker_count() -> None:
    queues = FakeQueues()
    assert await seed_organization(queues, workers=3) == 1  # ty: ignore[invalid-argument-type]
    assert queues.inserted == [QueueRow("Main", "main", 3, 0, True)]
