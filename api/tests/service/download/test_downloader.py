from pathlib import Path

import httpx
import pytest
from src.core.error import Error
from src.service.download.downloader import Downloader, Stopped
from src.service.download.progress import ProgressSample

BODY = b"0123456789" * 10  # 100 bytes


def _client(handler: object) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))  # ty: ignore[invalid-argument-type]


def _ok(request: httpx.Request) -> httpx.Response:
    return httpx.Response(200, content=BODY, headers={"content-length": str(len(BODY))})


@pytest.mark.asyncio
async def test_fetch_writes_the_whole_body(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    async with _client(_ok) as client:
        written = await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch("https://cdn.test/f", dest)
    assert written == 100
    assert dest.read_bytes() == BODY


@pytest.mark.asyncio
async def test_fetch_sends_a_range_header_when_resuming(tmp_path: Path) -> None:
    seen: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers.get("range"))
        return httpx.Response(206, content=BODY[40:], headers={"content-range": f"bytes 40-99/{len(BODY)}"})

    dest = tmp_path / "out.bin"
    dest.write_bytes(BODY[:40])

    async with _client(handler) as client:
        written = await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
            "https://cdn.test/f", dest, resume_from=40
        )

    assert seen == ["bytes=40-"]
    assert written == 100
    assert dest.read_bytes() == BODY


@pytest.mark.asyncio
async def test_a_server_that_ignores_range_restarts_from_zero(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    dest.write_bytes(b"stale-partial")

    async with _client(_ok) as client:
        written = await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
            "https://cdn.test/f", dest, resume_from=13
        )

    assert written == 100
    assert dest.read_bytes() == BODY


@pytest.mark.asyncio
async def test_progress_samples_are_delivered(tmp_path: Path) -> None:
    samples: list[ProgressSample] = []

    async def collect(sample: ProgressSample) -> None:
        samples.append(sample)

    async with _client(_ok) as client:
        await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
            "https://cdn.test/f", tmp_path / "out.bin", on_sample=collect
        )

    assert samples
    assert samples[-1].downloaded_bytes == 100
    assert samples[-1].progress == 100


@pytest.mark.asyncio
async def test_should_stop_raises_and_keeps_the_partial_file(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"

    async with _client(_ok) as client:
        with pytest.raises(Stopped):
            await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
                "https://cdn.test/f", dest, should_stop=lambda: True
            )

    assert dest.exists()


@pytest.mark.asyncio
async def test_a_403_is_retryable(tmp_path: Path) -> None:
    async with _client(lambda request: httpx.Response(403)) as client:
        with pytest.raises(Error) as caught:
            await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
                "https://cdn.test/f", tmp_path / "out.bin"
            )
    assert caught.value.retry_able is True


@pytest.mark.asyncio
async def test_a_500_is_retryable(tmp_path: Path) -> None:
    async with _client(lambda request: httpx.Response(500)) as client:
        with pytest.raises(Error) as caught:
            await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
                "https://cdn.test/f", tmp_path / "out.bin"
            )
    assert caught.value.retry_able is True


@pytest.mark.asyncio
async def test_a_404_is_permanent(tmp_path: Path) -> None:
    async with _client(lambda request: httpx.Response(404)) as client:
        with pytest.raises(Error) as caught:
            await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
                "https://cdn.test/f", tmp_path / "out.bin"
            )
    assert caught.value.retry_able is False


@pytest.mark.asyncio
async def test_a_transport_failure_is_retryable(tmp_path: Path) -> None:
    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no route to host")

    async with _client(boom) as client:
        with pytest.raises(Error) as caught:
            await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
                "https://cdn.test/f", tmp_path / "out.bin"
            )
    assert caught.value.retry_able is True


@pytest.mark.asyncio
async def test_a_206_reports_the_whole_file_size_not_the_remainder(tmp_path: Path) -> None:
    samples: list[ProgressSample] = []

    async def collect(sample: ProgressSample) -> None:
        samples.append(sample)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            206,
            content=BODY[40:],
            headers={"content-range": f"bytes 40-99/{len(BODY)}", "content-length": "60"},
        )

    dest = tmp_path / "out.bin"
    dest.write_bytes(BODY[:40])

    async with _client(handler) as client:
        await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch(
            "https://cdn.test/f", dest, resume_from=40, on_sample=collect
        )

    # Content-Range's total, not Content-Length's remainder: otherwise a resumed
    # download would report 100% at 60 of 100 bytes.
    assert samples[-1].total_bytes == 100
    assert samples[-1].progress == 100


@pytest.mark.asyncio
async def test_fetch_never_preallocates(tmp_path: Path) -> None:
    """Single-stream resume reads the file's own size, so a preallocated file
    would report itself complete before a single byte had arrived."""
    dest = tmp_path / "out.bin"
    sizes: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        sizes.append(dest.stat().st_size if dest.exists() else -1)
        return httpx.Response(200, content=BODY, headers={"content-length": str(len(BODY))})

    async with _client(handler) as client:
        await Downloader(client, chunk_size=16, flush_interval_ms=0).fetch("https://cdn.test/f", dest)

    assert sizes == [-1]
    assert dest.read_bytes() == BODY
