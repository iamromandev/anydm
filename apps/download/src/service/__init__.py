from collections.abc import AsyncGenerator
from typing import Annotated

from fastapi import Depends

from src.client import (
    CacheClient,
    get_cache_client,
)

from ..data.repo.interface.task import TaskRepo
from ..data.repo.task import TaskDbRepo, get_task_db_repo
from .download import DownloadService
from .health import HealthService


async def get_health_service(
    cache_client: Annotated[CacheClient, Depends(get_cache_client)]
) -> AsyncGenerator[HealthService]:
    yield HealthService(cache_client)


async def get_download_service(
    task_db_repo: Annotated[TaskRepo, Depends(get_task_db_repo)]
) -> AsyncGenerator[DownloadService]:
    yield DownloadService(
        task_db_repo=task_db_repo
    )
