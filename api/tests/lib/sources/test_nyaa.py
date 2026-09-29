"""Nyaa's RSS: the nyaa: namespace, sizes in KiB to TiB, magnets built from the info hash."""

from datetime import datetime
from pathlib import Path

import httpx
import pytest
from src.lib.sources.nyaa import Nyaa, parse
from src.lib.sources.source import SourceError

FEED = (Path(__file__).parents[2] / "fixtures" / "sources" / "nyaa_search.xml").read_bytes()


def test_items_become_results_with_a_magnet_and_no_link() -> None:
    first = parse(FEED)[0]

    assert first.title.startswith("[Erai-raws] Sousou no Frieren 2nd Season")
    assert (first.seeders, first.leechers, first.category) == (32, 4, "tv")
    assert first.size == 13314398617
    assert first.info_hash == "ef3e7ad1b12bdd9fc341691d8866cd1fa8374a4b"
    assert first.published == datetime(2026, 9, 12, 15, 45)
    assert first.link is None and first.indexers == ("nyaa",)
    assert first.magnet is not None and first.magnet.startswith("magnet:?xt=urn:btih:ef3e7ad1b12bdd9fc341691d8866cd1fa8374a4b&dn=%5BErai-raws%5D")


def test_an_item_without_an_info_hash_is_dropped() -> None:
    assert [r.title for r in parse(FEED)] == [
        "[Erai-raws] Sousou no Frieren 2nd Season [1080p CR WEBRip HEVC AAC][MultiSub] (unofficial batch)",
        "Frieren OST FLAC",
    ]


def test_sizes_in_mebibytes_and_a_zero_seed_count() -> None:
    ost = parse(FEED)[1]

    assert (ost.size, ost.seeders, ost.category) == (327680000, 0, "music")


def test_bad_xml_is_unreadable() -> None:
    with pytest.raises(SourceError) as caught:
        parse(b"<html>blocked")

    assert caught.value.message == "unreadable answer"


@pytest.mark.parametrize(
    ("category", "supported"),
    [("all", True), ("tv", True), ("music", True), ("software", True), ("books", True), ("movies", False), ("other", False)],
)
def test_it_covers_the_categories_nyaa_has_a_code_for(category: str, supported: bool) -> None:
    assert Nyaa().supports("frieren", category) is supported
    assert Nyaa().supports("", category) is supported


@pytest.mark.asyncio
async def test_a_search_sends_q_and_the_category_code_and_a_browse_sends_no_q() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, content=FEED)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        source = Nyaa(base="https://mirror.test/")
        await source.fetch(client, "frieren", "music", 1)
        await source.fetch(client, "", "all", 1)

    assert seen[0].url.host == "mirror.test"
    assert dict(seen[0].url.params) == {"page": "rss", "q": "frieren", "c": "2_0"}
    assert dict(seen[1].url.params) == {"page": "rss", "c": "0_0"}
