"""A URL that knows how to replace itself, exactly once per expiry.

YouTube stream URLs expire within hours and bind to the requesting IP. Every
segment of a part discovers that at the same instant, as a 403 arriving within
milliseconds of its siblings'. Without the lock below, one expiry means one
blocking yt-dlp extraction per segment — slow, and the shape of request burst
that earns a rate limit.

The generation check is the ``stale`` argument: a caller says which URL failed
for it, and a caller holding an already-replaced URL is told the new one without
anybody resolving anything.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable

UrlProvider = Callable[[], Awaitable[str]]


class UrlSource:
    def __init__(self, provider: UrlProvider) -> None:
        self._provider = provider
        self._url: str | None = None
        self._lock = asyncio.Lock()

    async def current(self) -> str:
        if self._url is not None:
            return self._url
        async with self._lock:
            if self._url is None:
                self._url = await self._provider()
            return self._url

    async def refresh(self, stale: str) -> str:
        """Replace ``stale``, unless somebody already has."""
        if self._url is not None and self._url != stale:
            return self._url
        async with self._lock:
            if self._url is not None and self._url != stale:
                return self._url
            self._url = await self._provider()
            return self._url

    def pin(self, url: str) -> None:
        """Adopt a URL the caller already resolved — a post-redirect one, say."""
        self._url = url
