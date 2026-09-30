"""YouTube's videos for some words, through the site client (spec: YouTube search)."""

import asyncio
import time
from collections.abc import Callable
from datetime import UTC, datetime

from src.data.schema.search import SearchVideosSchema, VideoSchema
from src.lib.site import error as site_error
from src.lib.site.client import SiteClient, VideoHit

#: How long a search is answered again without asking YouTube. A failure is never kept.
VIDEO_TTL_S = 60


def _published(hit: VideoHit) -> str | None:
    if hit.timestamp is None:
        return None
    return datetime.fromtimestamp(hit.timestamp, UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class VideoSearchService:
    def __init__(
        self,
        client: SiteClient,
        timeout_s: float = 25.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._client = client
        self._timeout = timeout_s
        self._clock = clock
        self._kept: dict[tuple[str, int], tuple[float, SearchVideosSchema]] = {}

    async def search(self, query: str, limit: int) -> SearchVideosSchema:
        key = (query, limit)
        kept = self._kept.get(key)
        if kept is not None and self._clock() - kept[0] < VIDEO_TTL_S:
            return kept[1]

        started = time.monotonic()
        try:
            # A thread of the default pool, like a download's probe: yt-dlp blocks.
            hits = await asyncio.wait_for(asyncio.to_thread(self._client.search, query, limit=limit), self._timeout)
        except TimeoutError as exc:
            raise site_error.extraction_failed("YouTube took too long to answer") from exc

        answer = SearchVideosSchema(
            results=[
                VideoSchema(
                    title=h.title,
                    url=h.url,
                    channel=h.channel,
                    duration=h.duration,
                    thumbnail=h.thumbnail,
                    views=h.views,
                    published=_published(h),
                )
                for h in hits
            ],
            took_ms=round((time.monotonic() - started) * 1000),
        )
        # Old answers go when a new one is stored, so the map can't grow without bound.
        now = self._clock()
        self._kept = {k: v for k, v in self._kept.items() if now - v[0] < VIDEO_TTL_S}
        self._kept[key] = (now, answer)
        return answer
