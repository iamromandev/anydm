import redis.asyncio as redis

from src.client import CacheClient
from src.core.base import BaseService
from src.core.common import get_app_version
from src.core.type import Status
from src.data import get_db_health
from src.data.schema.health import HealthSchema


class HealthService(BaseService):
    _cache_client: CacheClient

    def __init__(self, cache_client: CacheClient) -> None:
        super().__init__()
        self._cache_client = cache_client

    async def check_cache_health(self) -> bool:
        try:
            await self._cache_client.ping()
            await self._cache_client.close()
            return True
        except redis.ConnectionError:
            return False

    async def check_health(self) -> HealthSchema:
        app_version = get_app_version()
        db_status = Status.SUCCESS if await get_db_health() else Status.ERROR
        cache_status = Status.SUCCESS if await self.check_cache_health() else Status.ERROR
        health: HealthSchema = HealthSchema(
            version=app_version, db=db_status, cache=cache_status
        )
        health.log()
        return health
