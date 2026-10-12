"""Range-segmented transfer, with the single-stream downloader as its floor.

This wraps ``Downloader`` rather than replacing it. Every reason not to segment
— a server that will not do ranges, a size nobody knows, a file too small to be
worth the round trips, a count of one — ends in the ordinary single-stream path,
so the fallback is the same code that has always moved these bytes.

Nothing here touches the database. The plan goes out through ``reconcile`` and
comes back as watermarks; progress goes out through ``on_sample``. The worker
owns every write.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable, Mapping
from pathlib import Path

import httpx
from loguru import logger

from src.core.error import Error
from src.core.type import Code, ErrorType
from src.service.download.downloader import Downloader, Stopped
from src.service.download.probe import probe
from src.service.download.progress import (
    AggregateSample,
    ProgressAggregator,
    ProgressSample,
    ProgressTracker,
)
from src.service.download.rate_limit import Limiter, Unlimited
from src.service.download.segment import Segment, plan_segments, should_segment
from src.service.download.url_source import UrlSource
from src.service.download.writer import SegmentWriter

#: How often a segment recomputes its own speed. Short on purpose: these feed
#: the aggregator in memory, and the aggregator decides what reaches the disk.
_SEGMENT_SAMPLE_MS = 250
#: The writer's own timer, so a slow segment still reports before its buffer fills.
_WRITE_FLUSH_MS = 500

_RETRYABLE_STATUSES = frozenset({403, 408, 409, 425, 429, 500, 502, 503, 504})

Reconcile = Callable[[list[Segment]], Awaitable[tuple[dict[int, int], bool]]]


def _status_error(status: int) -> Error:
    retry_able = status in _RETRYABLE_STATUSES
    return Error.create(
        code=Code.BAD_GATEWAY,
        message=f"Upstream returned status {status}",
        error_type=ErrorType.EXTERNAL_API_ERROR if retry_able else ErrorType.DOES_NOT_EXIST,
        retry_able=retry_able,
    )


def _transport_error(exc: Exception) -> Error:
    return Error.create(
        code=Code.BAD_GATEWAY,
        message=f"Transfer failed: {exc}",
        error_type=ErrorType.DEPENDENCY_FAILURE,
        retry_able=True,
    )


class _RangeIgnored(Exception):
    """A server answered a range request with a whole body.

    The probe said ranges worked and this response says otherwise — usually a
    different CDN node. Not an error: the transfer restarts single-stream.
    """


class _Expired(Exception):
    """A 403 that a fresh URL will probably fix."""


def _flatten(group: BaseException) -> list[BaseException]:
    if isinstance(group, BaseExceptionGroup):
        return [leaf for child in group.exceptions for leaf in _flatten(child)]
    return [group]


def _unwrap(group: BaseExceptionGroup) -> BaseException:
    """The one exception the caller should see.

    ``TaskGroup`` wraps everything, so a pause would reach the worker as an
    ``ExceptionGroup`` and slip past its ``except Stopped:`` into the generic
    handler that marks a task failed. Order matters: a stop beats a failure,
    because a stop is what the user asked for.
    """
    leaves = _flatten(group)
    for kind in (Stopped, _RangeIgnored, Error):
        for leaf in leaves:
            if isinstance(leaf, kind):
                return leaf
    return leaves[0]


class SegmentedDownloader:
    def __init__(
        self,
        client: httpx.AsyncClient,
        fallback: Downloader,
        *,
        chunk_size: int,
        flush_interval_ms: int,
        min_segment_bytes: int,
        write_buffer_bytes: int,
        max_segment_attempts: int = 3,
        segment_backoff: tuple[float, ...] = (0.5, 2.0),
        limiter: Limiter | None = None,
    ) -> None:
        self._client = client
        self._limiter = limiter or Unlimited()
        self._fallback = fallback
        self._chunk_size = chunk_size
        self._flush_interval_ms = flush_interval_ms
        self._min_segment_bytes = min_segment_bytes
        self._write_buffer_bytes = write_buffer_bytes
        self._max_segment_attempts = max_segment_attempts
        self._backoff = segment_backoff

    async def fetch(
        self,
        source: UrlSource,
        dest: Path,
        *,
        count: int,
        reconcile: Reconcile,
        on_probe: Callable[[int | None], Awaitable[None]] | None = None,
        on_sample: Callable[[AggregateSample], Awaitable[None]] | None = None,
        on_discard: Callable[[], Awaitable[None]] | None = None,
        should_stop: Callable[[], bool] | None = None,
    ) -> int:
        url = await source.current()
        found = await probe(self._client, url, source.headers)
        # Before anything is written: the caller's last chance to refuse a
        # source whose size it has only just learned.
        if on_probe is not None:
            await on_probe(found.total_bytes)
        # Segments should not each re-walk the redirect chain.
        source.pin(found.resolved_url)

        if not should_segment(
            total_bytes=found.total_bytes,
            accepts_ranges=found.accepts_ranges,
            count=count,
            min_bytes=self._min_segment_bytes,
        ):
            # An empty plan matches a part that was never segmented, and fails to
            # match one that was — whose ``.part`` is preallocated to the full
            # size and would otherwise resume from past its own end.
            _, stale = await reconcile([])
            if stale:
                dest.unlink(missing_ok=True)
            return await self._single(found.resolved_url, dest, on_sample, should_stop, source.headers)

        total = found.total_bytes or 0
        plan = plan_segments(total, count)
        watermarks, fresh = await reconcile(plan)
        if fresh:
            # Nothing on disk describes these ranges. Resuming into them would
            # produce a file of exactly the right size and the wrong contents.
            dest.unlink(missing_ok=True)

        writer = SegmentWriter(
            dest,
            total,
            {segment.index: segment.end for segment in plan},
            buffer_bytes=self._write_buffer_bytes,
            flush_interval_ms=_WRITE_FLUSH_MS,
        )
        await writer.open()
        aggregator = ProgressAggregator(plan, flush_interval_ms=self._flush_interval_ms, started_at=time.monotonic())
        for segment in plan:
            aggregator.record(
                segment.index,
                downloaded=watermarks.get(segment.index, 0),
                speed_bps=0,
                at=time.monotonic(),
            )

        restart = False
        try:
            try:
                async with asyncio.TaskGroup() as group:
                    for segment in plan:
                        group.create_task(
                            self._run_segment(
                                source,
                                writer,
                                aggregator,
                                segment,
                                watermarks.get(segment.index, 0),
                                on_sample,
                                should_stop,
                            )
                        )
            except BaseExceptionGroup as failures:
                error = _unwrap(failures)
                if isinstance(error, _RangeIgnored):
                    restart = True
                else:
                    raise error from None
        finally:
            # Whatever happened — finished, paused, failed — the watermarks the
            # caller persists must match what is actually on disk.
            await writer.flush_all()
            for segment in plan:
                resume = segment.start + watermarks.get(segment.index, 0)
                aggregator.record(
                    segment.index,
                    downloaded=writer.high_water(segment.index, default=resume) - segment.start,
                    speed_bps=0,
                    at=time.monotonic(),
                )
            if on_sample is not None and not restart:
                await on_sample(aggregator.snapshot(time.monotonic()))
            await writer.close(fsync=True)

        if restart:
            logger.warning("SegmentedDownloader|{}: range ignored mid-transfer, restarting", dest.name)
            dest.unlink(missing_ok=True)
            if on_discard is not None:
                await on_discard()
            return await self._single(await source.current(), dest, on_sample, should_stop, source.headers)

        # Not ``dest.stat().st_size``: the file was preallocated, so it has
        # reported the full size since before a byte arrived. Completion has to
        # come from the engine's own accounting or it is a tautology.
        delivered = aggregator.snapshot(time.monotonic()).downloaded_bytes
        if delivered != total:
            raise Error.internal(message=f"Segments delivered {delivered} of {total} bytes")
        return delivered

    async def _single(
        self,
        url: str,
        dest: Path,
        on_sample: Callable[[AggregateSample], Awaitable[None]] | None,
        should_stop: Callable[[], bool] | None,
        headers: Mapping[str, str] | None = None,
    ) -> int:
        """The unsegmented path, reported in the same shape as a segmented one."""

        async def adapt(sample: ProgressSample) -> None:
            if on_sample is None:
                return
            await on_sample(
                AggregateSample(
                    downloaded_bytes=sample.downloaded_bytes,
                    total_bytes=sample.total_bytes,
                    progress=sample.progress,
                    speed_bps=sample.speed_bps,
                    eta_seconds=sample.eta_seconds,
                    segments=(),
                )
            )

        resume_from = dest.stat().st_size if dest.exists() else 0
        return await self._fallback.fetch(
            url, dest, resume_from=resume_from, headers=headers, on_sample=adapt, should_stop=should_stop
        )

    async def _run_segment(
        self,
        source: UrlSource,
        writer: SegmentWriter,
        aggregator: ProgressAggregator,
        segment: Segment,
        watermark: int,
        on_sample: Callable[[AggregateSample], Awaitable[None]] | None,
        should_stop: Callable[[], bool] | None,
    ) -> None:
        """Retry this range, and only this range.

        Four sockets meet roughly four times the transient failures of one.
        Without this tier every blip would restart the whole part and throw away
        three healthy segments' work.
        """
        attempt = 0
        while True:
            attempt += 1
            url = await source.current()
            resume = writer.high_water(segment.index, default=segment.start + watermark)
            try:
                await self._transfer(url, source.headers, writer, aggregator, segment, resume, on_sample, should_stop)
                return
            except (Stopped, _RangeIgnored):
                raise
            except _Expired:
                if attempt >= self._max_segment_attempts:
                    raise _status_error(403) from None
                # No backoff: the URL was the problem, not the server.
                await source.refresh(url)
            except (Error, httpx.HTTPError) as exc:
                error = exc if isinstance(exc, Error) else _transport_error(exc)
                if not error.retry_able or attempt >= self._max_segment_attempts:
                    raise error from None
                logger.warning(
                    "SegmentedDownloader|segment {} attempt {}: {}",
                    segment.index,
                    attempt,
                    error.message,
                )
                await asyncio.sleep(self._backoff[min(attempt, len(self._backoff)) - 1])

    async def _transfer(
        self,
        url: str,
        headers: Mapping[str, str],
        writer: SegmentWriter,
        aggregator: ProgressAggregator,
        segment: Segment,
        position: int,
        on_sample: Callable[[AggregateSample], Awaitable[None]] | None,
        should_stop: Callable[[], bool] | None,
    ) -> None:
        if position > segment.end:
            return

        tracker = ProgressTracker(
            total_bytes=segment.length,
            initial_bytes=position - segment.start,
            flush_interval_ms=_SEGMENT_SAMPLE_MS,
            started_at=time.monotonic(),
        )

        async with self._client.stream(
            "GET",
            url,
            # The format's headers, with our Range winning over any it carries.
            headers={**headers, "Range": f"bytes={position}-{segment.end}"},
            follow_redirects=True,
        ) as response:
            if response.status_code == 200:
                raise _RangeIgnored
            if response.status_code == 403:
                raise _Expired
            if response.status_code >= 400:
                raise _status_error(response.status_code)

            async for chunk in response.aiter_bytes(self._chunk_size):
                if should_stop is not None and should_stop():
                    raise Stopped
                # One byte past ``end`` belongs to the next segment.
                room = segment.end + 1 - position
                if room <= 0:
                    break
                chunk = chunk[:room]
                await self._limiter.acquire(len(chunk))
                high = await writer.write(segment.index, position, chunk)
                position += len(chunk)
                sample = tracker.record(len(chunk), at=time.monotonic())
                if sample is None:
                    continue
                aggregate = aggregator.record(
                    segment.index,
                    downloaded=high - segment.start,
                    speed_bps=sample.speed_bps,
                    at=time.monotonic(),
                )
                if aggregate is not None and on_sample is not None:
                    await on_sample(aggregate)

        high = await writer.flush(segment.index)
        aggregator.record(segment.index, downloaded=high - segment.start, speed_bps=0, at=time.monotonic())

        if position <= segment.end:
            # The body ended before the range did. Retryable: the bytes that did
            # arrive are on disk and the next attempt resumes from them.
            raise Error.create(
                code=Code.BAD_GATEWAY,
                message=f"Segment {segment.index} ended {segment.end + 1 - position} bytes short",
                error_type=ErrorType.DEPENDENCY_FAILURE,
                retry_able=True,
            )
