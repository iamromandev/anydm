"""Fixtures for tests that need a real Postgres.

Every test here is marked ``integration`` and excluded from ``make test``. Run
them with ``make test-all``, which points them at ``anydm_test``. The fixture
empties every download table, so it refuses any database not named ``*_test``.
"""

import uuid
from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from src.config import get_settings
from src.data.db import DB_CONFIG
from src.data.type import DOWNLOADS_ID, SEEDED_CATEGORIES
from tortoise import Tortoise

_TABLES = (
    "config.preference, iam.user, play.playback_position, "
    "transfer.segment, transfer.attempt, transfer.mirror, transfer.file, transfer.media, transfer.download, "
    "transfer.category, torrent.torrent, catalog.source, catalog.provider, shared.url, shared.tag"
)

#: Every category the migration seeds; each wipe puts them back, so the downloads that point at them stay valid.
_SEED = (
    "INSERT INTO transfer.category (id, name, slug, folder, position, builtin, created_at, updated_at) VALUES "
    + ", ".join(
        f"('{DOWNLOADS_ID if slug == 'downloads' else uuid.uuid4()}', '{name}', '{slug}', '{folder}', {position}, "
        f"{'TRUE' if slug == 'downloads' else 'FALSE'}, now(), now())"
        for position, (name, slug, folder) in enumerate(SEEDED_CATEGORIES)
    )
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
    await conn.execute_script(_SEED)


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
