from pathlib import Path
from typing import Any

import httpx
import pytest
from src.service.download.downloader import Downloader
from src.service.download.progress import AggregateSample
from src.service.download.segment import Segment
from src.service.download.segmented import SegmentedDownloader
from src.service.download.url_source import UrlSource

pytestmark = pytest.mark.asyncio

BODY = bytes(range(256)) * 16  # 4096 bytes, every byte position distinguishable


def _range_handler(
    body: bytes = BODY,
    *,
    seen: list[tuple[int, int]] | None = None,
    accept_ranges: bool = True,
):
    """A server that honours Range, recording every range it was asked for."""

    def handler(request: httpx.Request) -> httpx.Response:
        header = request.headers.get("range")
        if header is None or not accept_ranges:
            return httpx.Response(200, content=body, headers={"content-length": str(len(body))})
        start_text, _, end_text = header.removeprefix("bytes=").partition("-")
        start = int(start_text)
        end = int(end_text) if end_text else len(body) - 1
        if seen is not None:
            seen.append((start, end))
        chunk = body[start : end + 1]
        return httpx.Response(
            206,
            content=chunk,
            headers={
                "content-range": f"bytes {start}-{end}/{len(body)}",
                "content-length": str(len(chunk)),
            },
        )

    return handler


def _client(handler: Any) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def _engine(client: httpx.AsyncClient, *, min_bytes: int = 0) -> SegmentedDownloader:
    return SegmentedDownloader(
        client,
        Downloader(client, chunk_size=64, flush_interval_ms=0),
        chunk_size=64,
        flush_interval_ms=0,
        min_segment_bytes=min_bytes,
        write_buffer_bytes=128,
    )


def _source(url: str = "https://cdn.test/f") -> UrlSource:
    async def provider() -> str:
        return url

    return UrlSource(provider)


async def _fresh(plan: list[Segment]) -> tuple[dict[int, int], bool]:
    return {segment.index: 0 for segment in plan}, True


async def test_four_segments_reconstruct_the_file_exactly(tmp_path: Path) -> None:
    dest = tmp_path / "out.part"
    async with _client(_range_handler()) as client:
        written = await _engine(client).fetch(_source(), dest, count=4, reconcile=_fresh)
    assert written == len(BODY)
    assert dest.read_bytes() == BODY


async def test_the_ranges_requested_are_the_plan(tmp_path: Path) -> None:
    seen: list[tuple[int, int]] = []
    async with _client(_range_handler(seen=seen)) as client:
        await _engine(client).fetch(_source(), tmp_path / "out.part", count=4, reconcile=_fresh)
    # The first entry is the probe's one-byte request.
    assert seen[0] == (0, 0)
    assert sorted(seen[1:]) == [(0, 1023), (1024, 2047), (2048, 3071), (3072, 4095)]


async def test_a_resume_asks_only_for_what_is_missing(tmp_path: Path) -> None:
    dest = tmp_path / "out.part"
    dest.write_bytes(bytearray(len(BODY)))

    async def reconcile(plan: list[Segment]) -> tuple[dict[int, int], bool]:
        return {0: 1024, 1: 500, 2: 0, 3: 0}, False

    seen: list[tuple[int, int]] = []
    async with _client(_range_handler(seen=seen)) as client:
        await _engine(client).fetch(_source(), dest, count=4, reconcile=reconcile)

    # Segment 0 was already complete, so it asks for nothing at all.
    assert sorted(seen[1:]) == [(1524, 2047), (2048, 3071), (3072, 4095)]
    assert dest.read_bytes()[1524:] == BODY[1524:]


async def test_a_fresh_plan_discards_the_stale_part_file(tmp_path: Path) -> None:
    dest = tmp_path / "out.part"
    dest.write_bytes(b"nonsense from an older plan")
    async with _client(_range_handler()) as client:
        await _engine(client).fetch(_source(), dest, count=4, reconcile=_fresh)
    assert dest.read_bytes() == BODY


async def test_a_server_without_range_support_falls_back_to_one_stream(tmp_path: Path) -> None:
    dest = tmp_path / "out.part"
    async with _client(_range_handler(accept_ranges=False)) as client:
        written = await _engine(client).fetch(_source(), dest, count=4, reconcile=_fresh)
    assert written == len(BODY)
    assert dest.read_bytes() == BODY


async def test_a_small_file_is_not_worth_splitting(tmp_path: Path) -> None:
    seen: list[tuple[int, int]] = []
    dest = tmp_path / "out.part"
    async with _client(_range_handler(seen=seen)) as client:
        await _engine(client, min_bytes=1 << 30).fetch(_source(), dest, count=4, reconcile=_fresh)
    assert dest.read_bytes() == BODY
    assert len(seen) == 1  # the probe, and nothing else ranged


async def test_count_of_one_is_the_off_switch(tmp_path: Path) -> None:
    seen: list[tuple[int, int]] = []
    dest = tmp_path / "out.part"
    async with _client(_range_handler(seen=seen)) as client:
        await _engine(client).fetch(_source(), dest, count=1, reconcile=_fresh)
    assert dest.read_bytes() == BODY
    assert len(seen) == 1


async def test_samples_carry_every_segment(tmp_path: Path) -> None:
    samples: list[AggregateSample] = []

    async def collect(sample: AggregateSample) -> None:
        samples.append(sample)

    async with _client(_range_handler()) as client:
        await _engine(client).fetch(
            _source(), tmp_path / "out.part", count=4, reconcile=_fresh, on_sample=collect
        )

    assert samples
    final = samples[-1]
    assert final.downloaded_bytes == len(BODY)
    assert final.total_bytes == len(BODY)
    assert final.progress == 100
    assert [segment.index for segment in final.segments] == [0, 1, 2, 3]
    assert all(segment.downloaded == 1024 for segment in final.segments)


async def test_the_fallback_path_reports_no_segments(tmp_path: Path) -> None:
    samples: list[AggregateSample] = []

    async def collect(sample: AggregateSample) -> None:
        samples.append(sample)

    async with _client(_range_handler(accept_ranges=False)) as client:
        await _engine(client).fetch(
            _source(), tmp_path / "out.part", count=4, reconcile=_fresh, on_sample=collect
        )

    assert samples
    assert samples[-1].segments == ()
