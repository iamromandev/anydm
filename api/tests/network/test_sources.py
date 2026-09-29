"""Real requests to the built-in sources: a change in a site's shape shows up here, not in production."""

import httpx
import pytest
from src.lib.sources.apibay import Apibay
from src.lib.sources.eztv import Eztv
from src.lib.sources.nyaa import Nyaa

pytestmark = [pytest.mark.network, pytest.mark.asyncio]


async def _fetch(source: Apibay | Nyaa | Eztv, q: str) -> None:
    async with httpx.AsyncClient(headers={"User-Agent": "anydm"}, follow_redirects=True) as client:
        results = await source.fetch(client, q, "all", 20)
    assert results, f"{source.name} answered nothing for {q!r}"
    assert all(r.title and r.info_hash and r.magnet for r in results)


async def test_apibay_search_answers_and_parses() -> None:
    await _fetch(Apibay(), "ubuntu")


async def test_apibay_latest_answers_and_parses() -> None:
    await _fetch(Apibay(), "")


async def test_nyaa_search_answers_and_parses() -> None:
    await _fetch(Nyaa(), "frieren")


async def test_nyaa_latest_answers_and_parses() -> None:
    await _fetch(Nyaa(), "")


async def test_eztv_latest_answers_and_parses() -> None:
    await _fetch(Eztv(), "")
