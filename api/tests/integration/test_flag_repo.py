"""The app_flag repository against a real Postgres. Point DB_NAME at a scratch database, never the dev one."""

from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from src.data.db import DB_CONFIG
from src.data.db.model import AppFlag
from src.data.repo import AppFlagDatabaseRepo
from tortoise import Tortoise

pytestmark = pytest.mark.integration


@pytest_asyncio.fixture
async def flags() -> AsyncIterator[AppFlagDatabaseRepo]:
    await Tortoise.init(config=DB_CONFIG)
    saved = [f.name for f in await AppFlag.all()]
    await AppFlag.all().delete()
    yield AppFlagDatabaseRepo()
    await AppFlag.all().delete()
    if saved:
        await AppFlag.bulk_create([AppFlag(name=name) for name in saved])
    await Tortoise.close_connections()


@pytest.mark.asyncio
async def test_unset_then_set_then_set_again(flags: AppFlagDatabaseRepo) -> None:
    assert await flags.is_set("search_indexers_imported") is False

    await flags.mark("search_indexers_imported")

    assert await flags.is_set("search_indexers_imported") is True

    await flags.mark("search_indexers_imported")

    assert await flags.is_set("search_indexers_imported") is True
