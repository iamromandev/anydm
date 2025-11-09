from collections.abc import AsyncGenerator
from typing import Annotated

from fastapi import Depends
from pydantic import Field

from src.client import (
    CacheClient,
    get_cache_client,
)
from src.core.config import settings

from .health import HealthService


async def get_health_service(
    cache_client: CacheClient = Depends(get_cache_client)
) -> AsyncGenerator[HealthService]:
    yield HealthService(cache_client)
