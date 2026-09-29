"""Real requests to the built-in sources: a change in a site's shape shows up here, not in production."""

import httpx
import pytest
from src.lib.sources.apibay import Apibay

pytestmark = [pytest.mark.network, pytest.mark.asyncio]


async def _fetch(source: Apibay, q: str) -> None:
    async with httpx.AsyncClient(headers={"User-Agent": "anydm"}, follow_redirects=True) as client:
        results = await source.fetch(client, q, "all", 20)
    assert results, f"{source.name} answered nothing for {q!r}"
    assert all(r.title and r.info_hash and r.magnet for r in results)


async def test_apibay_search_answers_and_parses() -> None:
    await _fetch(Apibay(), "ubuntu")


async def test_apibay_latest_answers_and_parses() -> None:
    await _fetch(Apibay(), "")
