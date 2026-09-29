"""apibay: numbers as strings or integers, magnets from hashes, one category rule for search and browse."""

from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest
from src.lib.sources.apibay import Apibay, parse
from src.lib.sources.source import SourceError

FIXTURES = Path(__file__).parents[2] / "fixtures" / "sources"
SEARCH = (FIXTURES / "apibay_search.json").read_bytes()
RECENT = (FIXTURES / "apibay_recent.json").read_bytes()
NONE = (FIXTURES / "apibay_none.json").read_bytes()


def test_a_search_answer_becomes_results_with_a_magnet_and_no_link() -> None:
    first = parse(SEARCH, "all")[0]

    assert first.title == "Mastering Debian Linux. From Beginner to Advanced 2024"
    assert (first.size, first.seeders, first.leechers) == (4392928, 21, 1)
    assert first.published == datetime.fromtimestamp(1739866046, UTC)
    assert (first.category, first.link, first.indexers) == ("books", None, ("apibay",))
    assert first.info_hash == "029830a40e0fd9eca4cabb8f8c0e24a9e8c5a03f"
    assert first.magnet is not None and first.magnet.startswith("magnet:?xt=urn:btih:029830a40e0fd9eca4cabb8f8c0e24a9e8c5a03f&dn=Mastering")


def test_names_are_unescaped_and_adult_results_are_dropped() -> None:
    results = parse(SEARCH, "all")

    assert [r.title for r in results][1] == "The Debian Administrator's Handbook [Bullseye - 11] by Raphaël Hertzog EPUB"
    assert all("Cosplay" not in r.title for r in results)
    assert len(results) == 3


def test_the_latest_list_reads_integers_and_keeps_a_zero_seed_count() -> None:
    results = parse(RECENT, "all")

    assert [r.title for r in results] == ["General.Hospital.S64E15.Monday.28.September.2026.WEB.H264.DME", "Big Buck Bunny 2008 2160p"]
    assert (results[0].seeders, results[0].leechers) == (0, 0)
    assert results[1].category == "movies"


@pytest.mark.parametrize(
    ("body", "category", "titles"),
    [
        (SEARCH, "books", ["Mastering Debian Linux. From Beginner to Advanced 2024", "The Debian Administrator's Handbook [Bullseye - 11] by Raphaël Hertzog EPUB"]),
        (SEARCH, "software", ["Debian 13 netinst amd64"]),
        (SEARCH, "movies", []),
        (SEARCH, "other", []),
        (RECENT, "tv", ["General.Hospital.S64E15.Monday.28.September.2026.WEB.H264.DME"]),
        (RECENT, "movies", ["Big Buck Bunny 2008 2160p"]),
        (RECENT, "music", []),
    ],
)
def test_one_category_rule_narrows_search_and_browse(body: bytes, category: str, titles: list[str]) -> None:
    assert [r.title for r in parse(body, category)] == titles


def test_the_no_results_placeholder_is_an_empty_list() -> None:
    assert parse(NONE, "all") == []


@pytest.mark.parametrize("body", [b"<html>blocked</html>", b'{"error": "nope"}'])
def test_an_answer_that_is_not_a_list_is_unreadable(body: bytes) -> None:
    with pytest.raises(SourceError) as caught:
        parse(body, "all")

    assert caught.value.message == "unreadable answer"


def test_it_takes_every_request() -> None:
    assert Apibay().supports("", "all") and Apibay().supports("debian", "other")


@pytest.mark.asyncio
async def test_a_search_asks_q_php_with_its_group_and_a_browse_asks_the_latest_list() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, content=NONE)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        source = Apibay(base="https://mirror.test/")
        await source.fetch(client, "debian", "books", 1)
        await source.fetch(client, "", "all", 1)

    assert (seen[0].url.host, seen[0].url.path) == ("mirror.test", "/q.php")
    assert (seen[0].url.params["q"], seen[0].url.params["cat"]) == ("debian", "600")
    assert seen[1].url.path == "/precompiled/data_top100_recent.json"


@pytest.mark.asyncio
async def test_a_refusal_is_said_in_words() -> None:
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(403))) as client:
        with pytest.raises(SourceError) as caught:
            await Apibay().fetch(client, "debian", "all", 1)

    assert caught.value.message == "refused the request"
