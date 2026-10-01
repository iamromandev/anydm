"""Managing the built-in sources: what a client sees, what changes, what Test says."""

from collections.abc import Awaitable, Callable
from typing import Any

import httpx
import pytest
from src.core.error import Error
from src.core.type import ErrorType
from src.data.repo.search.interface.source import SearchSourceRow
from src.lib.sources.apibay import Apibay
from src.lib.sources.eztv import Eztv
from src.lib.sources.registry import BUILTINS
from src.service.search.source_settings import SourceSettingsService

from .fake_source_repo import FakeSourceRepo

APIBAY = BUILTINS["apibay"]
APIBAY_ANSWER = (
    b'[{"id":"1","name":"Ubuntu 24.04","info_hash":"' + b"a" * 40 + b'","seeders":"5","leechers":"1","size":"10","added":"1790000000","category":"303"}]'
)


def _service(
    repo: FakeSourceRepo,
    handler: Callable[[httpx.Request], Any] = lambda r: httpx.Response(200, content=APIBAY_ANSWER),
) -> SourceSettingsService:
    return SourceSettingsService(repo, httpx.AsyncClient(transport=httpx.MockTransport(handler)), timeout_s=1)


@pytest.mark.asyncio
async def test_the_list_shows_every_builtin_in_order_with_defaults_where_there_is_no_row() -> None:
    repo = FakeSourceRepo([SearchSourceRow("nyaa", "nyaa", False, "https://mirror.test", None)])

    listed = (await _service(repo).list_sources()).sources

    assert [s.name for s in listed] == ["apibay", "nyaa", "eztv"]
    assert (listed[0].label, listed[0].enabled, listed[0].base_url, listed[0].default_url) == ("apibay", True, APIBAY.default_url, APIBAY.default_url)
    assert (listed[1].label, listed[1].enabled, listed[1].base_url) == ("Nyaa", False, "https://mirror.test")


@pytest.mark.asyncio
async def test_update_turns_a_source_off_and_changes_its_address_trimmed() -> None:
    repo = FakeSourceRepo()
    service = _service(repo)

    off = await service.update("apibay", enabled=False, base_url=None)
    moved = await service.update("apibay", enabled=None, base_url=" https://mirror.test/ ")

    assert off.enabled is False
    assert (moved.enabled, moved.base_url) == (False, "https://mirror.test")
    stored = {r.name: r for r in await repo.list_all()}
    assert stored["apibay"] == SearchSourceRow("apibay", "apibay", False, "https://mirror.test", None, stored["apibay"].id)


@pytest.mark.asyncio
@pytest.mark.parametrize("address", ["", "ftp://x.test", "apibay.org", "https://a b.test"])
async def test_a_bad_address_is_a_422_and_changes_nothing(address: str) -> None:
    repo = FakeSourceRepo()

    with pytest.raises(Error) as caught:
        await _service(repo).update("apibay", enabled=None, base_url=address)

    assert caught.value.type == ErrorType.UNPROCESSABLE_ENTITY
    assert repo.rows == {}


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "call",
    [
        lambda s: s.update("nope", True, None),
        lambda s: s.reset("nope"),
        lambda s: s.test("nope", None),
    ],
)
async def test_an_unknown_source_is_404(call: Callable[[SourceSettingsService], Awaitable[Any]]) -> None:
    with pytest.raises(Error) as caught:
        await call(_service(FakeSourceRepo()))

    assert caught.value.type == ErrorType.SOURCE_NOT_FOUND


@pytest.mark.asyncio
async def test_reset_restores_the_constants() -> None:
    repo = FakeSourceRepo([SearchSourceRow("apibay", "apibay", False, "https://mirror.test", None)])

    reset = await _service(repo).reset("apibay")

    assert (reset.enabled, reset.base_url) == (True, APIBAY.default_url)
    stored = {r.name: r for r in await repo.list_all()}
    assert stored["apibay"] == SearchSourceRow("apibay", "apibay", True, APIBAY.default_url, None, stored["apibay"].id)


@pytest.mark.asyncio
async def test_only_enabled_sources_are_built_and_at_their_stored_address() -> None:
    repo = FakeSourceRepo([SearchSourceRow("nyaa", "nyaa", False, "https://n.test", None), SearchSourceRow("eztv", "eztv", True, "https://mirror.test", None)])

    sources = await _service(repo).enabled_sources()

    assert sources == [Apibay(base=APIBAY.default_url), Eztv(base="https://mirror.test")]


@pytest.mark.asyncio
async def test_a_working_source_tests_ok_with_what_it_found_and_asks_its_own_query() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, content=APIBAY_ANSWER)

    result = await _service(FakeSourceRepo(), handler).test("apibay", None)

    assert (result.ok, result.count, result.message) == (True, 1, "Answered")
    assert seen[0].url.params["q"] == "ubuntu"


@pytest.mark.asyncio
async def test_testing_an_unsaved_address_asks_that_address_even_for_a_disabled_source() -> None:
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.host)
        return httpx.Response(200, content=APIBAY_ANSWER)

    repo = FakeSourceRepo([SearchSourceRow("apibay", "apibay", False, APIBAY.default_url, None)])

    result = await _service(repo, handler).test("apibay", "https://mirror.test")

    assert result.ok is True and seen == ["mirror.test"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("respond", "message"),
    [
        (lambda r: httpx.Response(403), "refused the request"),
        (lambda r: httpx.Response(503), "answered 503"),
        (lambda r: httpx.Response(200, content=b"<html>"), "unreadable answer"),
    ],
)
async def test_a_source_that_fails_is_a_200_with_the_reason(respond: Callable[[httpx.Request], httpx.Response], message: str) -> None:
    result = await _service(FakeSourceRepo(), respond).test("apibay", None)

    assert (result.ok, result.count, result.message) == (False, None, message)


@pytest.mark.asyncio
async def test_a_source_that_can_not_be_reached_or_is_too_slow_says_so() -> None:
    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    def slow(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow", request=request)

    assert (await _service(FakeSourceRepo(), down).test("apibay", None)).message == "couldn't reach it"
    assert (await _service(FakeSourceRepo(), slow).test("apibay", None)).message == "timed out after 1 s"
