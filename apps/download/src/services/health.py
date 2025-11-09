from src.client import CacheClient
from src.core.base import BaseService
from src.core.common import get_app_version, get_cache_health
from src.core.type import Status
from src.db import get_db_health
from src.schemas.health import HealthSchema


class HealthService(BaseService):
    _cache_client: CacheClient

    def __init__(self, cache_client: CacheClient) -> None:
        super().__init__()
        self._cache_client = cache_client

    async def check_health(self) -> HealthSchema:
        app_version = get_app_version()
        db_status = Status.SUCCESS if await get_db_health() else Status.ERROR
        cache_status = Status.SUCCESS if await get_cache_health(self._cache_client) else Status.ERROR
        health: HealthSchema = HealthSchema(
            version=app_version, db=db_status, cache=cache_status
        )
        health.log()
        return health
