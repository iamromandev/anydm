"""Pull a URL to a file, resumably. Knows nothing about YouTube or tasks."""

from __future__ import annotations

import time
from collections.abc import Awaitable, Callable
from pathlib import Path

import httpx
from loguru import logger

from src.core.error import Error
from src.core.type import Code, ErrorType
from src.service.download.progress import ProgressSample, ProgressTracker
from src.service.download.writer import SegmentWriter

#: Statuses worth trying again. 403 is here because an expired stream URL
#: presents as one, and re-resolving fixes it.
_RETRYABLE_STATUSES = frozenset({403, 408, 409, 425, 429, 500, 502, 503, 504})


class Stopped(Exception):
    """Raised when ``should_stop`` asked the transfer to end — a pause or a cancel.

    Not an ``Error``: it is a control signal, not a failure, and the caller
    decides which status the task lands in.
    """


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


class Downloader:
    def __init__(
        self,
        client: httpx.AsyncClient,
        chunk_size: int,
        flush_interval_ms: int,
        write_buffer_bytes: int = 1 << 20,
    ) -> None:
        self._client = client
        self._chunk_size = chunk_size
        self._flush_interval_ms = flush_interval_ms
        self._write_buffer_bytes = write_buffer_bytes

    async def fetch(
        self,
        url: str,
        dest: Path,
        *,
        resume_from: int = 0,
        on_sample: Callable[[ProgressSample], Awaitable[None]] | None = None,
        should_stop: Callable[[], bool] | None = None,
    ) -> int:
        """Stream ``url`` into ``dest``, returning the total bytes on disk.

        ``resume_from`` sends a ``Range`` request and appends. A server that
        answers 200 anyway has ignored the range, so the file is truncated and
        started over rather than silently corrupted by appending a full body to
        a partial one.
        """
        dest.parent.mkdir(parents=True, exist_ok=True)
        headers = {"Range": f"bytes={resume_from}-"} if resume_from > 0 else {}

        try:
            async with self._client.stream("GET", url, headers=headers, follow_redirects=True) as response:
                if response.status_code >= 400:
                    raise _status_error(response.status_code)

                resuming = resume_from > 0 and response.status_code == 206
                if resume_from > 0 and not resuming:
                    logger.warning("Downloader|fetch(): server ignored Range, restarting {}", dest.name)

                start_bytes = resume_from if resuming else 0
                total = self._total_bytes(response, start_bytes)
                tracker = ProgressTracker(
                    total_bytes=total,
                    initial_bytes=start_bytes,
                    flush_interval_ms=self._flush_interval_ms,
                    started_at=time.monotonic(),
                )

                if not resuming:
                    dest.write_bytes(b"")

                # ``total_bytes=None`` on purpose: this path resumes from the
                # file's own size, and a preallocated file would report itself
                # complete before a byte had arrived.
                writer = SegmentWriter(
                    dest,
                    None,
                    buffer_bytes=self._write_buffer_bytes,
                    flush_interval_ms=500,
                )
                await writer.open()
                try:
                    position = start_bytes
                    async for chunk in response.aiter_bytes(self._chunk_size):
                        if should_stop is not None and should_stop():
                            raise Stopped
                        await writer.write(0, position, chunk)
                        position += len(chunk)
                        sample = tracker.record(len(chunk), at=time.monotonic())
                        if sample is not None and on_sample is not None:
                            await on_sample(sample)
                finally:
                    await writer.close()

                if on_sample is not None:
                    await on_sample(tracker.snapshot(at=time.monotonic()))

                return dest.stat().st_size
        except (Stopped, Error):
            raise
        except httpx.HTTPError as exc:
            logger.error("Downloader|fetch({}): {}", url, exc)
            raise _transport_error(exc) from exc

    @staticmethod
    def _total_bytes(response: httpx.Response, start_bytes: int) -> int | None:
        """The full size of the file, not of this response.

        A 206 reports the remaining length in ``Content-Length`` and the whole
        size after the slash in ``Content-Range``; preferring the latter keeps
        the percentage honest across a resume.
        """
        content_range = response.headers.get("content-range")
        if content_range and "/" in content_range:
            tail = content_range.rsplit("/", 1)[1].strip()
            if tail.isdigit():
                return int(tail)
        length = response.headers.get("content-length")
        if length and length.isdigit():
            return int(length) + start_bytes
        return None
