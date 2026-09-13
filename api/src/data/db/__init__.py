from __future__ import annotations

from fastapi import FastAPI
from loguru import logger
from tortoise import Tortoise
from tortoise.contrib.fastapi import register_tortoise

from src.config import get_settings
from src.core.common import serialize

settings = get_settings()

DB_CONFIG = {
    "connections": {
        "default": {
            "engine": "tortoise.backends.asyncpg",
            "credentials": {
                "host": settings.db_host,
                "port": settings.db_port,
                "user": settings.db_user,
                "password": serialize(settings.db_password),
                "database": settings.db_name,
            },
        }
    },
    "apps": {
        "model": {
            "models": ["src.data.db.model"],
            "default_connection": "default",
            "migrations": "src.data.db.migration",
        },
    },
}


async def init() -> None:
    await Tortoise.init(config=DB_CONFIG)
    logger.info("Database connection established")


async def close() -> None:
    await Tortoise.close_connections()
    logger.info("Database connection closed")


async def get_db_health() -> bool:
    try:
        conn = Tortoise.get_connection("default")
        await conn.execute_query("SELECT 1 AS one")
        return True
    except Exception as error:
        logger.error("Error|get_db_health(): {}", error)
        return False


async def get_db_version() -> str | None:
    try:
        conn = Tortoise.get_connection("default")
        row_count, rows = await conn.execute_query("SELECT version() AS version")

        if row_count > 0 and rows:
            return str(rows[0]["version"])

        return None
    except Exception as error:
        logger.error("Error|get_db_version(): {}", error)
        return None


def init_db(app: FastAPI) -> None:
    register_tortoise(
        app,
        config=DB_CONFIG,
        generate_schemas=False,  # make a decision using settings Env
        add_exception_handlers=True,
    )


async def run_migration() -> None:
    from tortoise.migrations.api import migrate

    def progress(event: str, app_label: str, name: str) -> None:
        if event == "apply_start":
            logger.info("Applying migration {}.{} ...", app_label, name)
        elif event == "apply_done":
            logger.info("Applied {}.{}", app_label, name)

    try:
        #await init()
        await migrate(config=DB_CONFIG, progress=progress)
    except Exception:
        logger.exception("Tortoise migrate failed")
        raise
    finally:
        #await close()
        logger.info("Tortoise migrate finished")
