"""Ask every source at once, merge what they find, and fetch a result's .torrent (spec: magnet hub)."""

import asyncio
import base64
import time
from collections.abc import Callable, Sequence
from urllib.parse import urljoin

import httpx
from loguru import logger

from src.data.schema.search import (
    IndexerErrorSchema,
    SearchResultSchema,
    SearchSchema,
    SearchTorrentSchema,
    SourcesSchema,
)
from src.lib.sources.source import Result, Source
from src.lib.sources.torznab_source import TorznabSource
from src.lib.torznab.torznab import Indexer, link_allowed, merge, redact
from src.service.search import error as search_error
from src.service.search.failure import failure_message

MAX_REDIRECTS = 3
#: How long a browse answer is served without asking the indexers again.
BROWSE_TTL_S = 300


class SearchService:
    def __init__(
        self,
        sources: Sequence[Source],
        client: httpx.AsyncClient,
        timeout_s: float,
        limit: int,
        max_torrent_bytes: int = 10 * 1024 * 1024,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._sources = list(sources)
        #: The Torznab indexers among them: the only origins a result's link may come from.
        self._indexers: list[Indexer] = [s.indexer for s in self._sources if isinstance(s, TorznabSource)]
        self._client = client
        self._timeout = timeout_s
        self._limit = limit
        self._max_bytes = max_torrent_bytes
        self._clock = clock
        #: category -> (when it was stored, the answer); browse only.
        self._browsed: dict[str, tuple[float, SearchSchema]] = {}

    def sources(self) -> SourcesSchema:
        return SourcesSchema(enabled=bool(self._sources), indexers=[s.name for s in self._sources])

    async def search(self, q: str, category: str, fresh: bool = False) -> SearchSchema:
        """Search for ``q``, or, when it is empty, browse the latest releases (cached for BROWSE_TTL_S)."""
        if not self._sources:
            raise search_error.disabled()
        browsing = q == ""
        if browsing and not fresh:
            stored = self._browsed.get(category)
            if stored is not None and self._clock() - stored[0] < BROWSE_TTL_S:
                logger.info(f"SearchService|browse {category}: cached")
                return stored[1]
        asked = [s for s in self._sources if s.supports(q, category)]
        if not asked:
            return SearchSchema(results=[], errors=[], asked=[], took_ms=0)
        started = time.monotonic()
        answers = await asyncio.gather(*(self._ask(s, q, category) for s in asked))
        found = [a for a in answers if isinstance(a, list)]
        errors = [a for a in answers if isinstance(a, IndexerErrorSchema)]
        if not found:
            raise search_error.failed(errors)
        answer = SearchSchema(
            results=[_schema(r) for r in merge(found, self._limit, "newest" if browsing else "seeders")],
            errors=errors,
            asked=[s.name for s in asked],
            took_ms=round((time.monotonic() - started) * 1000),
        )
        if browsing and not errors:
            self._browsed[category] = (self._clock(), answer)
        return answer

    async def _ask(self, source: Source, q: str, category: str) -> list[Result] | IndexerErrorSchema:
        def failed(message: str) -> IndexerErrorSchema:
            logger.warning(f"SearchService|{source.name}: {message}")
            return IndexerErrorSchema(indexer=source.name, message=message)

        try:
            return await source.fetch(self._client, q, category, self._timeout)
        except Exception as error:
            message = failure_message(error, self._timeout)
            if message is None:
                raise
            return failed(message)

    async def fetch_torrent(self, link: str) -> SearchTorrentSchema:
        """A result's .torrent, from its indexer only; a redirect to a magnet answers the magnet."""
        if not self._sources:
            raise search_error.disabled()
        if not link_allowed(link, self._indexers):
            raise search_error.link_not_from_indexer()
        current = link
        for _ in range(MAX_REDIRECTS + 1):
            try:
                async with self._client.stream("GET", current, timeout=self._timeout, follow_redirects=False) as response:
                    if response.is_redirect:
                        target = urljoin(current, response.headers.get("location", ""))
                        if target.startswith("magnet:"):
                            return SearchTorrentSchema(magnet=target)
                        if not link_allowed(target, self._indexers):
                            raise search_error.link_not_from_indexer()
                        current = target
                        continue
                    if response.status_code >= 400:
                        raise search_error.fetch_failed(f"the indexer answered {response.status_code}")
                    body = bytearray()
                    async for chunk in response.aiter_bytes():
                        body += chunk
                        if len(body) > self._max_bytes:
                            raise search_error.too_large(self._max_bytes)
            except httpx.HTTPError as error:
                logger.warning(f"SearchService|fetch {redact(current)}: {type(error).__name__}")
                raise search_error.fetch_failed("couldn't reach the indexer") from error
            if not body.startswith(b"d"):
                raise search_error.not_a_torrent()
            return SearchTorrentSchema(torrent=base64.b64encode(bytes(body)).decode())
        raise search_error.fetch_failed("too many redirects")


def _schema(result: Result) -> SearchResultSchema:
    return SearchResultSchema(
        title=result.title,
        size=result.size,
        seeders=result.seeders,
        leechers=result.leechers,
        published=result.published,
        category=result.category,
        info_hash=result.info_hash,
        magnet=result.magnet,
        link=result.link,
        indexers=list(result.indexers),
    )
