import pytest
from src.data.repo import QueueDatabaseRepo
from src.service.download.seed import seed_organization

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]


@pytest.mark.asyncio
async def test_the_fixture_seeded_and_seeding_again_adds_nothing() -> None:
    queues = QueueDatabaseRepo()
    assert (await queues.main()).max_concurrent == 2
    assert await seed_organization(queues, workers=2) == 0
