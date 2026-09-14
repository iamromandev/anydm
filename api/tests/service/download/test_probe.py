import httpx
import pytest
from src.core.error import Error
from src.service.download.probe import probe

pytestmark = pytest.mark.asyncio


def _client(handler: object) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))  # ty: ignore[invalid-argument-type]


async def test_a_206_reports_the_whole_size_and_range_support() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["range"] == "bytes=0-0"
        return httpx.Response(206, content=b"A", headers={"content-range": "bytes 0-0/4096"})

    async with _client(handler) as client:
        result = await probe(client, "https://cdn.test/f")

    assert result.accepts_ranges is True
    assert result.total_bytes == 4096


async def test_a_200_means_the_server_ignored_the_range() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"x" * 10, headers={"content-length": "10"})

    async with _client(handler) as client:
        result = await probe(client, "https://cdn.test/f")

    assert result.accepts_ranges is False
    assert result.total_bytes == 10


async def test_an_unknown_size_is_none_not_zero() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, stream=httpx.ByteStream(b"x"))

    async with _client(handler) as client:
        result = await probe(client, "https://cdn.test/f")

    assert result.total_bytes is None


async def test_the_post_redirect_url_comes_back() -> None:
    """Segments should not each re-walk the redirect chain."""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/start":
            return httpx.Response(302, headers={"location": "https://cdn.test/final"})
        return httpx.Response(206, content=b"A", headers={"content-range": "bytes 0-0/99"})

    async with _client(handler) as client:
        result = await probe(client, "https://cdn.test/start")

    assert result.resolved_url == "https://cdn.test/final"


async def test_an_error_status_raises_the_projects_error() -> None:
    async with _client(lambda request: httpx.Response(404)) as client:
        with pytest.raises(Error):
            await probe(client, "https://cdn.test/missing")


async def test_a_416_is_treated_as_no_range_support() -> None:
    """Some servers answer a zero-length range with 416 but serve the file fine."""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(416, headers={"content-range": "bytes */2048"})

    async with _client(handler) as client:
        result = await probe(client, "https://cdn.test/f")

    assert result.accepts_ranges is False
    assert result.total_bytes == 2048
