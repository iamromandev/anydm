"""Fixtures for tests that need a real Postgres.

Every test here is marked ``integration`` and excluded from ``make test``. Run
them with ``make test-all`` while ``make up`` is running: the root
``tests/conftest.py`` points ``DB_HOST``/``DB_PORT`` at the published
``localhost:5430``, which is the same database the container reaches as ``db``.
"""

from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from src.data.db import DB_CONFIG
from src.data.db.model import Task
from tortoise import Tortoise


@pytest_asyncio.fixture
async def db() -> AsyncIterator[None]:
    await Tortoise.init(config=DB_CONFIG)
    await Task.all().delete()
    yield
    await Task.all().delete()
    await Tortoise.close_connections()


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"
