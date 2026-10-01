"""Seed the search sources: the built-ins' defaults, then the .env indexers, once. Safe to run any time: neither step overwrites one."""

import asyncio

from src.data.db import DB_CONFIG
from src.data.repo import AppFlagDatabaseRepo, SearchSourceDatabaseRepo
from src.service.source.seed import import_env_indexers, seed_missing_sources
from tortoise import Tortoise


async def run() -> tuple[int, int]:
    await Tortoise.init(config=DB_CONFIG)
    try:
        repo = SearchSourceDatabaseRepo()
        return await seed_missing_sources(repo), await import_env_indexers(repo, AppFlagDatabaseRepo())
    finally:
        await Tortoise.close_connections()


def main() -> None:
    seeded, imported = asyncio.run(run())
    print(f"Seeded {seeded} search source(s), imported {imported} indexer(s)" if seeded or imported else "Every search source already has a row")


if __name__ == "__main__":
    main()
