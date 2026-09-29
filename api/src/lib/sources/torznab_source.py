"""A Torznab indexer (Prowlarr, Jackett) as a Source."""

from dataclasses import dataclass

import httpx

from src.lib.sources.source import Result, get
from src.lib.torznab.torznab import Indexer, build_url, parse


@dataclass(frozen=True)
class TorznabSource:
    indexer: Indexer

    @property
    def name(self) -> str:
        return self.indexer.name

    def supports(self, q: str, category: str) -> bool:
        return True

    async def fetch(self, client: httpx.AsyncClient, q: str, category: str, timeout_s: float) -> list[Result]:
        response = await get(client, build_url(self.indexer, q, category), timeout_s)
        return parse(response.content, self.indexer.name)
