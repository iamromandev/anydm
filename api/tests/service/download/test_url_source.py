import asyncio

import pytest
from src.service.download.url_source import UrlSource

pytestmark = pytest.mark.asyncio


class Counter:
    def __init__(self) -> None:
        self.calls = 0

    async def __call__(self) -> str:
        self.calls += 1
        await asyncio.sleep(0)
        return f"https://cdn.test/v{self.calls}"


async def test_the_first_read_resolves_once() -> None:
    provider = Counter()
    source = UrlSource(provider)
    assert await source.current() == "https://cdn.test/v1"
    assert await source.current() == "https://cdn.test/v1"
    assert provider.calls == 1


async def test_concurrent_readers_share_one_resolve() -> None:
    provider = Counter()
    source = UrlSource(provider)
    urls = await asyncio.gather(*(source.current() for _ in range(8)))
    assert provider.calls == 1
    assert set(urls) == {"https://cdn.test/v1"}


async def test_a_refresh_replaces_the_stale_url() -> None:
    provider = Counter()
    source = UrlSource(provider)
    stale = await source.current()
    assert await source.refresh(stale) == "https://cdn.test/v2"
    assert provider.calls == 2


async def test_segments_that_expire_together_refresh_once() -> None:
    """Four segments get their 403 within milliseconds of each other. Four
    independent pytubefix resolves is slow and earns a rate limit."""
    provider = Counter()
    source = UrlSource(provider)
    stale = await source.current()
    urls = await asyncio.gather(*(source.refresh(stale) for _ in range(4)))
    assert provider.calls == 2
    assert set(urls) == {"https://cdn.test/v2"}


async def test_a_refresh_against_an_already_replaced_url_is_a_no_op() -> None:
    provider = Counter()
    source = UrlSource(provider)
    stale = await source.current()
    await source.refresh(stale)
    assert await source.refresh(stale) == "https://cdn.test/v2"
    assert provider.calls == 2


async def test_pin_adopts_a_url_without_asking_the_provider() -> None:
    provider = Counter()
    source = UrlSource(provider)
    source.pin("https://cdn.test/after-redirect")
    assert await source.current() == "https://cdn.test/after-redirect"
    assert provider.calls == 0
