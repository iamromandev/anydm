"""Create the integration database if it is missing, and migrate it.

Refuses any database not named ``*_test``: the integration fixture empties
every download table in whatever database it reaches.
"""

import asyncio

import asyncpg
from src.config import get_settings
from src.core.common import serialize
from src.data.db import run_migration


async def main() -> None:
    settings = get_settings()
    if not settings.db_name.endswith("_test"):
        raise SystemExit(f"refusing to prepare {settings.db_name!r}: not a *_test database")
    conn = await asyncpg.connect(
        host=settings.db_host,
        port=settings.db_port,
        user=settings.db_user,
        password=serialize(settings.db_password),
        database="postgres",
    )
    try:
        if not await conn.fetchval("SELECT 1 FROM pg_database WHERE datname = $1", settings.db_name):
            await conn.execute(f'CREATE DATABASE "{settings.db_name}"')
    finally:
        await conn.close()
    await run_migration()


if __name__ == "__main__":
    asyncio.run(main())
