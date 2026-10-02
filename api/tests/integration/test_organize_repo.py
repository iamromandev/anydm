import pytest
from src.data.repo import CategoryDatabaseRepo, QueueDatabaseRepo
from src.service.download.seed import seed_organization

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]


@pytest.mark.asyncio
async def test_the_fixture_seeded_and_seeding_again_adds_nothing() -> None:
    categories, queues = CategoryDatabaseRepo(), QueueDatabaseRepo()
    assert [c.name for c in await categories.list_all()][-1] == "Other"
    assert (await queues.main()).max_concurrent == 2
    assert await seed_organization(categories, queues, workers=2) == (0, 0)
    assert (await categories.other()).extensions == []
