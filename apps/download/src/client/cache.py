from functools import cached_property
from typing import Annotated, Any

import redis.asyncio as redis
from pydantic import Field, RedisDsn

from src.core.factory import SingletonMeta
from src.core.formats import serialize


class CacheClient(metaclass=SingletonMeta):
    _initialized: Annotated[bool, Field(default=False)] = False
    _cache: Annotated[redis.Redis, Field(...)]

    def __init__(self, cache_url: Annotated[RedisDsn, Field(...)]) -> None:
        if self._initialized:
            return
        self._cache = redis.from_url(
            serialize(cache_url),
            decode_responses=True
        )
        self._initialized = True

    @cached_property
    def _tag(self) -> str:
        return self.__class__.__name__

    async def ping(self) -> Any:
        return await self._cache.ping()

    async def get(self, key: str) -> Any:
        return await self._cache.get(key)

    async def set(self, key: str, value: str, ttl: int = None) -> None:
        if ttl:
            await self._cache.setex(key, ttl, value)
        else:
            await self._cache.set(key, value)

    async def delete(self, key: str) -> None:
        await self._cache.delete(key)

    async def exists(self, key: str) -> bool:
        return await self._cache.exists(key) > 0

    async def expire(self, key: str, ttl: int) -> None:
        await self._cache.expire(key, ttl)

    async def close(self) -> None:
        await self._cache.close()
