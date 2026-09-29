"""SearchService against fake indexers: in parallel, failures in words, and fetching a .torrent."""

import asyncio
import base64
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx
import pytest
from src.core.error import Error
from src.core.type import ErrorType
from src.lib.torznab.torznab import Indexer
from src.service.search.search_service import SearchService

FIXTURES = Path(__file__).parents[2] / "fixtures" / "torznab"
PROWLARR = Indexer("prowlarr-1", "http://prowlarr:9696/1/api", "abc")
JACKETT = Indexer("jackett-all", "http://jackett:9117/api/v2.0/indexers/all/results/torznab", "def")
TORRENT = b"d8:announce3:urle"


def _service(
    handler: Callable[[httpx.Request], Any],
    indexers: list[Indexer] | None = None,
) -> SearchService:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return SearchService(indexers if indexers is not None else [PROWLARR, JACKETT], client, timeout_s=1, limit=100)


def _fixture(name: str) -> httpx.Response:
    return httpx.Response(200, content=(FIXTURES / name).read_bytes())


@pytest.mark.asyncio
async def test_both_indexers_answer_and_the_bunny_is_merged() -> None:
    asked: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        asked.append(request.url.host)
        assert request.url.params["q"] == "bunny"
        assert request.url.params["cat"] == "2000"
        return _fixture("prowlarr.xml" if request.url.host == "prowlarr" else "jackett.xml")

    answer = await _service(handler).search("bunny", "movies")

    assert sorted(asked) == ["jackett", "prowlarr"]
    assert answer.errors == []
    bunny = answer.results[0]
    assert (bunny.title, bunny.seeders, bunny.indexers) == ("Big Buck Bunny 1080p", 150, ["prowlarr-1", "jackett-all"])
    assert sorted(r.title for r in answer.results) == ["Big Buck Bunny 1080p", "Debian 13 netinst", "Sintel Soundtrack FLAC"]


@pytest.mark.asyncio
async def test_one_indexer_failing_is_a_line_not_a_failure() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "jackett":
            raise httpx.ReadTimeout("slow", request=request)
        return _fixture("prowlarr.xml")

    answer = await _service(handler).search("bunny", "all")

    assert [r.title for r in answer.results] == ["Big Buck Bunny 1080p", "Sintel Soundtrack FLAC"]
    assert [(e.indexer, e.message) for e in answer.errors] == [("jackett-all", "timed out after 1 s")]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("respond", "message"),
    [
        (lambda r: httpx.Response(401), "the indexer refused the key"),
        (lambda r: httpx.Response(503), "answered 503"),
        (lambda r: httpx.Response(200, content=b"<html>"), "unreadable answer"),
        (lambda r: _fixture("error.xml"), "Invalid API Key"),
    ],
)
async def test_every_indexer_failing_is_502_with_each_reason(respond: Callable[[httpx.Request], httpx.Response], message: str) -> None:
    with pytest.raises(Error) as caught:
        await _service(respond, [PROWLARR]).search("bunny", "all")

    assert caught.value.type == ErrorType.SEARCH_FAILED
    assert [(d.subject, d.description) for d in caught.value.details or []] == [("prowlarr-1", message)]


@pytest.mark.asyncio
async def test_unreachable_says_so() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(Error) as caught:
        await _service(handler, [PROWLARR]).search("bunny", "all")
    assert [d.description for d in caught.value.details or []] == ["couldn't reach it"]


@pytest.mark.asyncio
async def test_indexers_are_asked_at_the_same_time() -> None:
    running = 0
    peak = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal running, peak
        running += 1
        peak = max(peak, running)
        await asyncio.sleep(0.05)
        running -= 1
        return _fixture("prowlarr.xml")

    await _service(handler).search("bunny", "all")
    assert peak == 2


@pytest.mark.asyncio
async def test_no_indexers_is_search_disabled() -> None:
    service = _service(lambda r: httpx.Response(200), [])

    assert service.sources().enabled is False
    with pytest.raises(Error) as caught:
        await service.search("bunny", "all")
    assert caught.value.type == ErrorType.SEARCH_DISABLED


def test_sources_name_the_indexers_and_nothing_else() -> None:
    sources = _service(lambda r: httpx.Response(200)).sources()

    assert sources.model_dump() == {"enabled": True, "indexers": ["prowlarr-1", "jackett-all"]}


@pytest.mark.asyncio
async def test_a_torrent_link_is_fetched_as_base64() -> None:
    service = _service(lambda r: httpx.Response(200, content=TORRENT))

    answer = await service.fetch_torrent("http://prowlarr:9696/1/download?link=x")

    assert base64.b64decode(answer.torrent or "") == TORRENT
    assert answer.magnet is None


@pytest.mark.asyncio
async def test_a_redirect_to_a_magnet_answers_the_magnet() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"location": "magnet:?xt=urn:btih:aa"})

    answer = await _service(handler).fetch_torrent("http://jackett:9117/dl/x")

    assert answer.magnet == "magnet:?xt=urn:btih:aa"


@pytest.mark.asyncio
async def test_a_redirect_elsewhere_is_refused() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"location": "http://evil.test/x"})

    with pytest.raises(Error) as caught:
        await _service(handler).fetch_torrent("http://jackett:9117/dl/x")
    assert caught.value.type == ErrorType.LINK_NOT_FROM_INDEXER


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("link", "respond", "kind"),
    [
        ("http://evil.test/x", lambda r: httpx.Response(200, content=TORRENT), ErrorType.LINK_NOT_FROM_INDEXER),
        ("http://prowlarr:9696/dl", lambda r: httpx.Response(200, content=b"<html>login</html>"), ErrorType.NOT_A_TORRENT),
        ("http://prowlarr:9696/dl", lambda r: httpx.Response(200, content=b"d" + b"0" * 64), ErrorType.FILE_TOO_LARGE),
        ("http://prowlarr:9696/dl", lambda r: httpx.Response(404), ErrorType.BAD_GATEWAY),
    ],
)
async def test_a_fetch_that_goes_wrong_says_how(link: str, respond: Callable[[httpx.Request], httpx.Response], kind: ErrorType) -> None:
    client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
    service = SearchService([PROWLARR, JACKETT], client, timeout_s=1, limit=100, max_torrent_bytes=32)

    with pytest.raises(Error) as caught:
        await service.fetch_torrent(link)
    assert caught.value.type == kind
