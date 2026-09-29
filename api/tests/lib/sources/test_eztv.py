"""EZTV: the latest TV releases only; there is no text search."""

from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest
from src.lib.sources.eztv import Eztv, parse
from src.lib.sources.source import SourceError

LATEST = (Path(__file__).parents[2] / "fixtures" / "sources" / "eztv_latest.json").read_bytes()


def test_entries_become_tv_results_and_the_magnet_it_gave_is_kept() -> None:
    first = parse(LATEST)[0]

    assert first.title == "High Value Target The Hunt For Saddam S01E07 720p WEB H264-JFF EZTV"
    assert (first.size, first.seeders, first.leechers, first.category) == (654321987, 12, 3, "tv")
    assert first.published == datetime.fromtimestamp(1790650000, UTC)
    assert first.info_hash == "d0206eb8e03134b59f527bb5b924278b27963585"
    assert first.magnet is not None and first.magnet.startswith("magnet:?xt=urn:btih:d0206eb8e03134b59f527bb5b924278b27963585&dn=High+Value+Target")
    assert first.link is None and first.indexers == ("eztv",)


def test_a_missing_magnet_is_built_from_the_hash_and_a_zero_seed_count_stays_zero() -> None:
    second = parse(LATEST)[1]

    assert second.magnet is not None and second.magnet.startswith("magnet:?xt=urn:btih:e0206eb8e03134b59f527bb5b924278b27963586&dn=Some%20Show")
    assert second.seeders == 0


def test_an_entry_without_a_hash_is_dropped() -> None:
    assert len(parse(LATEST)) == 2


@pytest.mark.parametrize("body", [b"<html>", b"[]"])
def test_an_answer_that_is_not_eztvs_is_unreadable(body: bytes) -> None:
    with pytest.raises(SourceError) as caught:
        parse(body)

    assert caught.value.message == "unreadable answer"


@pytest.mark.parametrize(
    ("q", "category", "supported"),
    [("", "all", True), ("", "tv", True), ("", "movies", False), ("bunny", "all", False), ("bunny", "tv", False)],
)
def test_it_browses_all_and_tv_and_never_searches(q: str, category: str, supported: bool) -> None:
    assert Eztv().supports(q, category) is supported


@pytest.mark.asyncio
async def test_it_asks_for_a_hundred_of_the_latest() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, content=LATEST)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        await Eztv(base="https://mirror.test/").fetch(client, "", "tv", 1)

    assert (seen[0].url.host, seen[0].url.path) == ("mirror.test", "/api/get-torrents")
    assert dict(seen[0].url.params) == {"limit": "100", "page": "1"}
