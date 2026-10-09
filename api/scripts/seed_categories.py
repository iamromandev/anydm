"""Insert the built-in categories that have no row yet. Safe to run any time: it never overwrites one."""

import asyncio

from src.data.db import DB_CONFIG
from src.data.repo import CategoryDatabaseRepo
from src.service.category.seed import seed_missing_categories
from tortoise import Tortoise


async def run() -> int:
    await Tortoise.init(config=DB_CONFIG)
    try:
        return await seed_missing_categories(CategoryDatabaseRepo())
    finally:
        await Tortoise.close_connections()


def main() -> None:
    added = asyncio.run(run())
    print(f"Seeded {added} categor{'y' if added == 1 else 'ies'}" if added else "Every category already has a row")


if __name__ == "__main__":
    main()
