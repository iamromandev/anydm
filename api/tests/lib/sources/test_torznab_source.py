"""A Torznab indexer as a Source: the same URL, the same parsing, the same words for failures."""

from pathlib import Path

import httpx
import pytest
from src.lib.sources.source import SourceError
from src.lib.sources.torznab_source import TorznabSource
from src.lib.torznab.torznab import Indexer, TorznabError

FIXTURES = Path(__file__).parents[2] / "fixtures" / "torznab"
PROWLARR = Indexer("prowlarr-1", "http://prowlarr:9696/1/api", "abc")


def test_it_is_named_for_its_indexer_and_takes_every_request() -> None:
    source = TorznabSource(PROWLARR)

    assert source.name == "prowlarr-1"
    assert source.supports("bunny", "movies") and source.supports("", "all")


@pytest.mark.asyncio
async def test_it_asks_the_indexer_and_parses_the_answer() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, content=(FIXTURES / "prowlarr.xml").read_bytes())

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        results = await TorznabSource(PROWLARR).fetch(client, "bunny", "movies", 1)

    assert seen[0].url.params["q"] == "bunny" and seen[0].url.params["apikey"] == "abc"
    assert [r.title for r in results] == ["Big Buck Bunny 1080p", "Sintel Soundtrack FLAC"]
    assert results[0].indexers == ("prowlarr-1",)


@pytest.mark.asyncio
async def test_a_refused_key_and_a_bad_answer_raise_source_errors_in_the_old_words() -> None:
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(401))) as client:
        with pytest.raises(SourceError) as refused:
            await TorznabSource(PROWLARR).fetch(client, "x", "all", 1)
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200, content=b"<html>"))) as client:
        with pytest.raises(SourceError) as unreadable:
            await TorznabSource(PROWLARR).fetch(client, "x", "all", 1)

    assert refused.value.message == "the indexer refused the key"
    assert unreadable.value.message == "unreadable answer"
    assert isinstance(unreadable.value, TorznabError)
