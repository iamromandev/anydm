from collections.abc import AsyncGenerator
from typing import Annotated

from fastapi import Depends

from src.client import (
    CacheClient,
    get_cache_client,
)

from .download import DownloadService
from .health import HealthService


async def get_health_service(
    cache_client: CacheClient = Depends(get_cache_client)
) -> AsyncGenerator[HealthService]:
    yield HealthService(cache_client)


async def get_download_service(
) -> AsyncGenerator[DownloadService]:
    yield DownloadService()
