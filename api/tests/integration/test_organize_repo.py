import pytest
from src.data.repo import FolderDatabaseRepo, QueueDatabaseRepo
from src.service.download.seed import seed_organization

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]


@pytest.mark.asyncio
async def test_the_fixture_seeded_and_seeding_again_adds_nothing() -> None:
    folders, queues = FolderDatabaseRepo(), QueueDatabaseRepo()
    assert [f.name for f in await folders.list_all()][-1] == "Other"
    assert (await queues.main()).max_concurrent == 2
    assert await seed_organization(folders, queues, workers=2) == (0, 0)
    assert (await folders.other()).extensions == []
