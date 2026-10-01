"""Fixtures for tests that need a real Postgres.

Every test here is marked ``integration`` and excluded from ``make test``. Run
them with ``make test-all``, which points them at ``anydm_test``. The fixture
empties every download table, so it refuses any database not named ``*_test``.
"""

from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from src.config import get_settings
from src.data.db import DB_CONFIG
from src.data.repo import CategoryDatabaseRepo, QueueDatabaseRepo
from src.service.download.seed import seed_organization
from tortoise import Tortoise

_TABLES = (
    "playback_position, segment, mirror, download_file, site_detail, torrent_detail, "
    "download, collection, category, download_queue"
)


def _require_test_database() -> None:
    name = get_settings().db_name
    if not name.endswith("_test"):
        pytest.exit(
            f"Integration tests empty every download table; refusing database {name!r}. Run `make test-all`.",
            returncode=2,
        )


async def _wipe() -> None:
    await Tortoise.get_connection("default").execute_script(f"TRUNCATE {_TABLES} CASCADE")


@pytest_asyncio.fixture
async def db() -> AsyncIterator[None]:
    _require_test_database()
    await Tortoise.init(config=DB_CONFIG)
    await _wipe()
    await seed_organization(CategoryDatabaseRepo(), QueueDatabaseRepo(), workers=2)
    yield
    await _wipe()
    await Tortoise.close_connections()


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"
