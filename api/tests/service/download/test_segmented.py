from pathlib import Path
from typing import Any

import httpx
import pytest
from src.core.error import Error
from src.service.download.downloader import Downloader, Stopped
from src.service.download.progress import AggregateSample
from src.service.download.segment import Segment
from src.service.download.segmented import SegmentedDownloader
from src.service.download.url_source import Target, UrlSource

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


def _engine(
    client: httpx.AsyncClient,
    *,
    min_bytes: int = 0,
    max_segment_attempts: int = 3,
) -> SegmentedDownloader:
    return SegmentedDownloader(
        client,
        Downloader(client, chunk_size=64, flush_interval_ms=0),
        chunk_size=64,
        flush_interval_ms=0,
        min_segment_bytes=min_bytes,
        write_buffer_bytes=128,
        max_segment_attempts=max_segment_attempts,
        segment_backoff=(0.0, 0.0),
    )


def _source(url: str = "https://cdn.test/f") -> UrlSource:
    async def provider() -> str:
        return url

    return UrlSource(provider)


def _source_from(provider: Any) -> UrlSource:
    return UrlSource(provider)


async def _fresh(plan: list[Segment]) -> tuple[dict[int, int], bool]:
    return {segment.index: 0 for segment in plan}, True


async def test_four_segments_reconstruct_the_file_exactly(tmp_path: Path) -> None:
    dest = tmp_path / "out.part"
    async with _client(_range_handler()) as client:
        written = await _engine(client).fetch(_source(), dest, count=4, reconcile=_fresh)
    assert written == len(BODY)
    assert dest.read_bytes() == BODY


async def test_on_probe_learns_the_size_before_anything_is_written(tmp_path: Path) -> None:
    dest = tmp_path / "out.part"
    seen: list[tuple[int, int]] = []
    probed: list[int | None] = []

    async def refuse(total: int | None) -> None:
        probed.append(total)
        raise Error.conflict(message="no room")

    async with _client(_range_handler(seen=seen)) as client:
        with pytest.raises(Error):
            await _engine(client).fetch(
                _source(), dest, count=4, reconcile=_fresh, on_probe=refuse
            )

    assert probed == [len(BODY)]
    # Only the probe's one-byte request went out, and no file was created.
    assert seen == [(0, 0)]
    assert not dest.exists()


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
    # The probe, then one request for the whole file: ranged from 0 (#72), not split.
    assert seen == [(0, 0), (0, len(BODY) - 1)]


async def test_count_of_one_is_the_off_switch(tmp_path: Path) -> None:
    seen: list[tuple[int, int]] = []
    dest = tmp_path / "out.part"
    async with _client(_range_handler(seen=seen)) as client:
        await _engine(client).fetch(_source(), dest, count=1, reconcile=_fresh)
    assert dest.read_bytes() == BODY
    assert seen == [(0, 0), (0, len(BODY) - 1)]


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


async def test_a_pause_surfaces_as_a_bare_stopped(tmp_path: Path) -> None:
    """TaskGroup wraps everything in an ExceptionGroup. The worker's
    `except Stopped:` would miss that and mark a paused task failed."""
    async with _client(_range_handler()) as client:
        with pytest.raises(Stopped):
            await _engine(client).fetch(
                _source(),
                tmp_path / "out.part",
                count=4,
                reconcile=_fresh,
                should_stop=lambda: True,
            )


async def test_a_pause_leaves_the_watermarks_matching_the_disk(tmp_path: Path) -> None:
    samples: list[AggregateSample] = []

    async def collect(sample: AggregateSample) -> None:
        samples.append(sample)

    # Counted, not timed: the per-segment sample timer is 250 ms, so a 4 KiB
    # body would finish long before any mid-transfer sample could fire.
    checks = {"left": 10}

    def should_stop() -> bool:
        checks["left"] -= 1
        return checks["left"] <= 0

    dest = tmp_path / "out.part"
    async with _client(_range_handler()) as client:
        with pytest.raises(Stopped):
            await _engine(client).fetch(
                _source(),
                dest,
                count=4,
                reconcile=_fresh,
                on_sample=collect,
                should_stop=should_stop,
            )

    final = samples[-1]
    assert 0 < final.downloaded_bytes < len(BODY)
    # Every byte a watermark claims is really on disk. A watermark that ran
    # ahead of the writer would resume past data that never landed.
    written = dest.read_bytes()
    for segment in final.segments:
        end = segment.start + segment.downloaded
        assert written[segment.start : end] == BODY[segment.start : end]


async def test_one_flaky_segment_retries_without_disturbing_its_siblings(tmp_path: Path) -> None:
    failures = {"left": 1}
    inner = _range_handler()

    def handler(request: httpx.Request) -> httpx.Response:
        if request.headers.get("range") == "bytes=1024-2047" and failures["left"]:
            failures["left"] -= 1
            return httpx.Response(503)
        return inner(request)

    dest = tmp_path / "out.part"
    async with _client(handler) as client:
        await _engine(client).fetch(_source(), dest, count=4, reconcile=_fresh)

    assert failures["left"] == 0
    assert dest.read_bytes() == BODY


async def test_a_segment_that_exhausts_its_attempts_fails_the_part(tmp_path: Path) -> None:
    inner = _range_handler()

    def handler(request: httpx.Request) -> httpx.Response:
        if request.headers.get("range") == "bytes=1024-2047":
            return httpx.Response(503)
        return inner(request)

    async with _client(handler) as client:
        with pytest.raises(Error):
            await _engine(client, max_segment_attempts=2).fetch(
                _source(), tmp_path / "out.part", count=4, reconcile=_fresh
            )


async def test_an_expired_url_is_refreshed_once_for_every_segment(tmp_path: Path) -> None:
    """All four segments 403 at the same instant. One resolve, not four."""
    resolves = {"count": 0}

    async def provider() -> str:
        resolves["count"] += 1
        return f"https://cdn.test/v{resolves['count']}"

    inner = _range_handler()

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/v1" and request.headers.get("range") not in (None, "bytes=0-0"):
            return httpx.Response(403)
        return inner(request)

    dest = tmp_path / "out.part"
    async with _client(handler) as client:
        await _engine(client).fetch(_source_from(provider), dest, count=4, reconcile=_fresh)

    assert dest.read_bytes() == BODY
    assert resolves["count"] == 2


async def test_a_200_answering_a_range_restarts_single_stream(tmp_path: Path) -> None:
    """Appending a whole body into a positional write is the silent
    corruption case, so the attempt is torn down instead."""
    discarded = {"count": 0}

    async def on_discard() -> None:
        discarded["count"] += 1

    def handler(request: httpx.Request) -> httpx.Response:
        header = request.headers.get("range")
        if header == "bytes=0-0":
            return httpx.Response(206, content=BODY[:1], headers={"content-range": f"bytes 0-0/{len(BODY)}"})
        return httpx.Response(200, content=BODY, headers={"content-length": str(len(BODY))})

    dest = tmp_path / "out.part"
    async with _client(handler) as client:
        written = await _engine(client).fetch(
            _source(), dest, count=4, reconcile=_fresh, on_discard=on_discard
        )

    assert written == len(BODY)
    assert dest.read_bytes() == BODY
    assert discarded["count"] == 1


async def test_a_body_longer_than_its_range_is_truncated(tmp_path: Path) -> None:
    """One byte past `end` is the next segment's region."""

    def handler(request: httpx.Request) -> httpx.Response:
        header = request.headers.get("range")
        if header == "bytes=0-0":
            return httpx.Response(206, content=BODY[:1], headers={"content-range": f"bytes 0-0/{len(BODY)}"})
        spec = header.removeprefix("bytes=")
        start = int(spec.partition("-")[0])
        end = int(spec.partition("-")[2])
        # Deliberately overshoots the requested end by 256 bytes.
        chunk = BODY[start : end + 257]
        return httpx.Response(206, content=chunk, headers={"content-range": f"bytes {start}-{end}/{len(BODY)}"})

    dest = tmp_path / "out.part"
    async with _client(handler) as client:
        await _engine(client).fetch(_source(), dest, count=4, reconcile=_fresh)

    assert dest.read_bytes() == BODY


async def test_a_body_shorter_than_its_range_is_retried(tmp_path: Path) -> None:
    truncate = {"left": 1}
    inner = _range_handler()

    def handler(request: httpx.Request) -> httpx.Response:
        header = request.headers.get("range")
        if header == "bytes=1024-2047" and truncate["left"]:
            truncate["left"] -= 1
            return httpx.Response(
                206,
                content=BODY[1024:1500],
                headers={"content-range": f"bytes 1024-2047/{len(BODY)}"},
            )
        return inner(request)

    dest = tmp_path / "out.part"
    async with _client(handler) as client:
        await _engine(client).fetch(_source(), dest, count=4, reconcile=_fresh)

    assert truncate["left"] == 0
    assert dest.read_bytes() == BODY


async def test_an_unsegmented_part_still_resumes_normally(tmp_path: Path) -> None:
    """An empty plan matches a part that was never segmented."""
    seen_plans: list[int] = []

    async def reconcile(plan: list[Segment]) -> tuple[dict[int, int], bool]:
        seen_plans.append(len(plan))
        return {}, False

    dest = tmp_path / "out.part"
    dest.write_bytes(BODY[:1000])
    async with _client(_range_handler()) as client:
        await _engine(client, min_bytes=1 << 30).fetch(_source(), dest, count=4, reconcile=reconcile)

    assert seen_plans == [0]
    assert dest.read_bytes() == BODY


async def test_a_part_that_was_segmented_is_discarded_when_it_no_longer_is(tmp_path: Path) -> None:
    """The leftover file is preallocated to the full size, so resuming from its
    st_size would ask for a range starting past the end of the file."""

    async def reconcile(plan: list[Segment]) -> tuple[dict[int, int], bool]:
        return {}, True

    dest = tmp_path / "out.part"
    dest.write_bytes(bytearray(len(BODY)))  # preallocated, empty, full-size
    async with _client(_range_handler()) as client:
        written = await _engine(client, min_bytes=1 << 30).fetch(
            _source(), dest, count=4, reconcile=reconcile
        )

    assert written == len(BODY)
    assert dest.read_bytes() == BODY


class _RecordingLimiter:
    def __init__(self) -> None:
        self.acquired: list[int] = []

    async def acquire(self, size: int) -> None:
        self.acquired.append(size)


async def test_every_segment_shares_the_one_limiter(tmp_path: Path) -> None:
    limiter = _RecordingLimiter()
    async with _client(_range_handler()) as client:
        engine = SegmentedDownloader(
            client,
            Downloader(client, chunk_size=64, flush_interval_ms=0, limiter=limiter),
            chunk_size=64,
            flush_interval_ms=0,
            min_segment_bytes=0,
            write_buffer_bytes=128,
            limiter=limiter,
        )
        await engine.fetch(_source(), tmp_path / "out.part", count=4, reconcile=_fresh)
    assert sum(limiter.acquired) == len(BODY)


async def test_the_unsegmented_fallback_is_limited_too(tmp_path: Path) -> None:
    limiter = _RecordingLimiter()
    async with _client(_range_handler(accept_ranges=False)) as client:
        engine = SegmentedDownloader(
            client,
            Downloader(client, chunk_size=64, flush_interval_ms=0, limiter=limiter),
            chunk_size=64,
            flush_interval_ms=0,
            min_segment_bytes=0,
            write_buffer_bytes=128,
            limiter=limiter,
        )
        await engine.fetch(_source(), tmp_path / "out.part", count=4, reconcile=_fresh)
    assert sum(limiter.acquired) == len(BODY)


def _headed_source(url: str = "https://cdn.test/f") -> UrlSource:
    async def provider() -> Target:
        return Target(url, {"Referer": "https://site.test/"})

    return UrlSource(provider)


def _recording(seen: list[str | None]):
    handler = _range_handler()

    def record(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers.get("referer"))
        return handler(request)

    return record


async def test_the_format_s_headers_reach_the_probe_and_every_segment(tmp_path: Path) -> None:
    seen: list[str | None] = []
    async with _client(_recording(seen)) as client:
        await _engine(client).fetch(_headed_source(), tmp_path / "out.part", count=4, reconcile=_fresh)

    assert len(seen) == 5  # the probe and four segments
    assert set(seen) == {"https://site.test/"}


async def test_the_format_s_headers_reach_the_single_stream_too(tmp_path: Path) -> None:
    seen: list[str | None] = []
    async with _client(_recording(seen)) as client:
        await _engine(client, min_bytes=1 << 30).fetch(_headed_source(), tmp_path / "out.part", count=4, reconcile=_fresh)

    assert seen == ["https://site.test/", "https://site.test/"]
