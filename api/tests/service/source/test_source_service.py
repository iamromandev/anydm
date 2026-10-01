"""Every source as one list: what a client sees, what changes, what Test says."""

import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx
import pytest
from src.core.error import Error
from src.core.type import ErrorType
from src.data.schema.source import SourceSchema
from src.lib.sources.eztv import Eztv
from src.lib.sources.registry import BUILTINS
from src.lib.sources.torznab_source import TorznabSource
from src.service.source.source_service import SourceService

from ..search.fake_source_repo import FakeSourceRepo

APIBAY = BUILTINS["apibay"]
APIBAY_ANSWER = (
    b'[{"id":"1","name":"Ubuntu 24.04","info_hash":"' + b"a" * 40 + b'","seeders":"5","leechers":"1","size":"10","added":"1790000000","category":"303"}]'
)
FIXTURES = Path(__file__).parents[2] / "fixtures" / "torznab"


def _service(
    repo: FakeSourceRepo | None = None,
    handler: Callable[[httpx.Request], Any] = lambda r: httpx.Response(200, content=APIBAY_ANSWER),
) -> SourceService:
    return SourceService(repo or FakeSourceRepo(), httpx.AsyncClient(transport=httpx.MockTransport(handler)), timeout_s=1)


async def _seeded(handler: Callable[[httpx.Request], Any] = lambda r: httpx.Response(200, content=APIBAY_ANSWER)) -> SourceService:
    """A service whose stored rows are what the migration and the seed leave behind: the three registry rows."""
    service = _service(handler=handler)
    for builtin in BUILTINS.values():
        await service.create(builtin.name, builtin.name, builtin.default_url, None, builtin.default_enabled)
    return service


async def _named(service: SourceService, name: str) -> SourceSchema:
    return next(s for s in (await service.list()).sources if s.name == name)


@pytest.mark.asyncio
async def test_the_list_shows_registry_rows_first_then_created_oldest_first() -> None:
    service = _service()
    await service.create("prowlarr", "torznab", "http://p.test/1/api", api_key="key-1", enabled=True)
    for builtin in BUILTINS.values():
        await service.create(builtin.name, builtin.name, builtin.default_url, None, builtin.default_enabled)
    await service.create("nyaa-lan", "nyaa", "https://lan.test", api_key=None, enabled=True)

    listed = (await service.list()).sources

    assert [s.name for s in listed] == ["apibay", "nyaa", "eztv", "prowlarr", "nyaa-lan"]
    assert [s.deletable for s in listed] == [False, False, False, True, True]
    assert [s.default_url for s in listed] == [APIBAY.default_url, "https://nyaa.si", "https://eztvx.to", None, "https://nyaa.si"]
    assert [s.api_key_masked for s in listed] == [None, None, None, "********", None]
    assert listed[0].id is not None and listed[0].kind == "apibay"


@pytest.mark.asyncio
async def test_create_rejects_a_bad_name_an_unknown_kind_a_bad_address_and_a_key_where_it_means_nothing() -> None:
    service = await _seeded()

    with pytest.raises(Error) as bad_name:
        await service.create("Prowlarr", "torznab", "http://p.test/1/api", None, True)
    with pytest.raises(Error) as bad_kind:
        await service.create("r", "rss", "http://r.test", None, True)
    with pytest.raises(Error) as bad_address:
        await service.create("p", "torznab", "ftp://p.test/1/api", None, True)
    with pytest.raises(Error) as stray_key:
        await service.create("n", "nyaa", "https://n.test", "key-1", True)
    for caught in (bad_name, bad_kind, bad_address, stray_key):
        assert caught.value.type == ErrorType.UNPROCESSABLE_ENTITY

    assert [s.name for s in (await service.list()).sources] == ["apibay", "nyaa", "eztv"]


@pytest.mark.asyncio
async def test_create_refuses_a_name_that_is_already_taken() -> None:
    service = await _seeded()

    with pytest.raises(Error) as caught:
        await service.create("apibay", "apibay", "https://mirror.test", None, True)
    assert caught.value.type == ErrorType.UNPROCESSABLE_ENTITY

    assert (await _named(service, "apibay")).base_url == APIBAY.default_url


@pytest.mark.asyncio
async def test_an_omitted_key_is_kept_an_empty_one_clears() -> None:
    service = await _seeded()
    created = await service.create("prowlarr", "torznab", "http://p.test/1/api", "secret-key-1", True)

    kept = await service.update(created.id, enabled=None, base_url=None, api_key=None)
    assert kept.api_key_masked == "secr…ey-1"

    cleared = await service.update(created.id, enabled=None, base_url=None, api_key="")
    assert cleared.api_key_masked is None

    assert (await _named(service, "prowlarr")).api_key_masked is None


@pytest.mark.asyncio
async def test_delete_removes_a_created_source_but_never_a_registry_row() -> None:
    service = await _seeded()
    created = await service.create("prowlarr", "torznab", "http://p.test/1/api", None, True)
    registry_id = (await _named(service, "nyaa")).id

    await service.delete(created.id)

    assert [s.name for s in (await service.list()).sources] == ["apibay", "nyaa", "eztv"]
    with pytest.raises(Error) as caught:
        await service.delete(registry_id)
    assert caught.value.type == ErrorType.SOURCE_NOT_DELETABLE


@pytest.mark.asyncio
async def test_reset_restores_a_builtin_kind_row_but_a_torznab_row_has_no_default() -> None:
    service = await _seeded()
    own = await service.create("nyaa-lan", "nyaa", "https://lan.test", None, False)
    indexer = await service.create("prowlarr", "torznab", "http://p.test/1/api", "key-1", True)

    reset = await service.reset(own.id)

    assert (reset.enabled, reset.base_url) == (True, "https://nyaa.si")
    with pytest.raises(Error) as caught:
        await service.reset(indexer.id)
    assert caught.value.type == ErrorType.SOURCE_NO_DEFAULT


@pytest.mark.asyncio
async def test_an_unknown_id_is_404_everywhere() -> None:
    service = await _seeded()
    missing = uuid.uuid4()

    for call in (
        lambda: service.update(missing, True, None, None),
        lambda: service.delete(missing),
        lambda: service.reset(missing),
        lambda: service.test(missing, None, None),
    ):
        with pytest.raises(Error) as caught:
            await call()
        assert caught.value.type == ErrorType.SOURCE_NOT_FOUND


@pytest.mark.asyncio
async def test_a_working_source_tests_ok_with_what_it_found_and_asks_its_own_query() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, content=APIBAY_ANSWER)

    service = await _seeded(handler)
    apibay_id = (await _named(service, "apibay")).id

    result = await service.test(apibay_id, None, None)

    assert (result.ok, result.count, result.message) == (True, 1, "Answered")
    assert seen[0].url.params["q"] == "ubuntu"


@pytest.mark.asyncio
async def test_testing_an_unsaved_address_asks_that_address_even_for_a_disabled_source() -> None:
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(str(request.url.host))
        return httpx.Response(200, content=APIBAY_ANSWER)

    service = await _seeded(handler)
    stored_id = (await service.create("mirror", "apibay", APIBAY.default_url, None, False)).id

    result = await service.test(stored_id, "https://mirror.test", None)

    assert result.ok is True and seen == ["mirror.test"]


@pytest.mark.asyncio
async def test_probe_tests_a_source_that_is_not_saved_yet() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, content=(FIXTURES / "prowlarr.xml").read_bytes())

    result = await _service(handler=handler).probe("torznab", "http://p.test/1/api", "key-1")

    assert result.ok is True and (result.count or 0) > 0
    assert seen[0].url.params["q"] == "ubuntu"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("respond", "message"),
    [
        (lambda r: httpx.Response(403), "refused the request"),
        (lambda r: httpx.Response(503), "answered 503"),
        (lambda r: httpx.Response(200, content=b"<html>"), "unreadable answer"),
    ],
)
async def test_a_source_that_fails_is_a_200_with_the_reason(
    respond: Callable[[httpx.Request], httpx.Response], message: str
) -> None:
    service = await _seeded(respond)
    apibay_id = (await _named(service, "apibay")).id

    result = await service.test(apibay_id, None, None)

    assert (result.ok, result.count, result.message) == (False, None, message)


@pytest.mark.asyncio
async def test_a_torznab_refusal_uses_the_indexers_words() -> None:
    service = await _seeded(lambda r: httpx.Response(403))
    created = await service.create("prowlarr", "torznab", "http://p.test/1/api", None, True)

    result = await service.test(created.id, None, None)

    assert (result.ok, result.message) == (False, "the indexer refused the key")


@pytest.mark.asyncio
async def test_a_source_that_can_not_be_reached_or_is_too_slow_says_so() -> None:
    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    def slow(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow", request=request)

    down_service = await _seeded(down)
    slow_service = await _seeded(slow)

    assert (await down_service.test((await _named(down_service, "apibay")).id, None, None)).message == "couldn't reach it"
    assert (await slow_service.test((await _named(slow_service, "apibay")).id, None, None)).message == "timed out after 1 s"


@pytest.mark.asyncio
async def test_the_key_reaches_the_indexer() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, content=(FIXTURES / "prowlarr.xml").read_bytes())

    service = await _seeded(handler)
    created = await service.create("prowlarr", "torznab", "http://p.test/1/api", "key-1", True)

    await service.test(created.id, None, None)

    assert seen[0].url.params["apikey"] == "key-1"


@pytest.mark.asyncio
async def test_a_short_key_is_never_shown_whole() -> None:
    service = _service()
    short = await service.create("p", "torznab", "http://p.test/1/api", api_key="abc", enabled=True)
    long = await service.create("q", "torznab", "http://q.test/1/api", api_key="abcdefghij", enabled=True)
    assert short.api_key_masked == "********" and "abc" not in short.api_key_masked
    assert long.api_key_masked == "abcd…ghij"


@pytest.mark.asyncio
async def test_only_enabled_sources_are_built_and_at_their_stored_address_with_their_key() -> None:
    service = await _seeded()
    await service.update((await _named(service, "nyaa")).id, False, "https://n.test", None)
    await service.create("prowlarr", "torznab", "http://p.test/1/api", "key-1", True)

    sources = await service.enabled_sources()

    by_name = {s.name: s for s in sources}
    assert set(by_name) == {"apibay", "eztv", "prowlarr"}
    eztv = by_name["eztv"]
    assert isinstance(eztv, Eztv)
    assert eztv.base == "https://eztvx.to"
    prowlarr = by_name["prowlarr"]
    assert isinstance(prowlarr, TorznabSource)
    assert prowlarr.indexer.key == "key-1"
