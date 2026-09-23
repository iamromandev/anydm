"""Loguru configuration."""

from __future__ import annotations

import logging
import sys

from loguru import logger

from src.config.settings import get_settings


def configure_logging():
    settings = get_settings()

    logger.remove()

    # uvicorn's access log is stdlib logging, not loguru, and it prints each
    # request's full path. A key in the query would otherwise land there.
    from src.core.auth import RedactApiKey

    access = logging.getLogger("uvicorn.access")
    if not any(isinstance(f, RedactApiKey) for f in access.filters):
        access.addFilter(RedactApiKey())

    level = "DEBUG" if settings.debug else "INFO"

    if settings.log_format == "json":
        fmt = "{time:YYYY-MM-DDTHH:mm:ssZ} | {level} | {name}:{function}:{line} | {message}"
        logger.add(sys.stdout, level=level, format=fmt, serialize=True)
    else:
        fmt = "<green>{time:HH:mm:ss}</green> | <level>{level:<7}</level> | <cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> | <level>{message}</level>"
        logger.add(sys.stdout, level=level, format=fmt, colorize=True)
