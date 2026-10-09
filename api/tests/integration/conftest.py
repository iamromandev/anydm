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
from src.data.repo import CategoryDatabaseRepo
from src.service.category.seed import seed_missing_categories
from tortoise import Tortoise

_TABLES = (
    "config.preference, iam.user, play.playback_position, "
    "transfer.segment, transfer.attempt, transfer.mirror, transfer.file, transfer.media, transfer.download, "
    "transfer.category, torrent.torrent, catalog.source, catalog.provider, shared.url, shared.tag"
)


def _require_test_database() -> None:
    name = get_settings().db_name
    if not name.endswith("_test"):
        pytest.exit(
            f"Integration tests empty every download table; refusing database {name!r}. Run `make test-all`.",
            returncode=2,
        )


async def _wipe() -> None:
    conn = Tortoise.get_connection("default")
    await conn.execute_script(f"TRUNCATE {_TABLES} CASCADE")
    # The categories `make seed` writes, so the downloads that point at them stay valid.
    await seed_missing_categories(CategoryDatabaseRepo())


@pytest_asyncio.fixture
async def db() -> AsyncIterator[None]:
    _require_test_database()
    await Tortoise.init(config=DB_CONFIG)
    await _wipe()
    yield
    await _wipe()
    await Tortoise.close_connections()


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"
