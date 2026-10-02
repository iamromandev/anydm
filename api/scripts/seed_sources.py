"""Insert the built-in search sources that have no row yet. Safe to run any time: it never overwrites one."""

import asyncio

from src.data.db import DB_CONFIG
from src.data.repo import SourceDatabaseRepo
from src.service.source.seed import seed_missing_sources
from tortoise import Tortoise


async def run() -> int:
    await Tortoise.init(config=DB_CONFIG)
    try:
        return await seed_missing_sources(SourceDatabaseRepo())
    finally:
        await Tortoise.close_connections()


def main() -> None:
    added = asyncio.run(run())
    print(f"Seeded {added} search source(s)" if added else "Every search source already has a row")


if __name__ == "__main__":
    main()
