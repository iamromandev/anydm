import asyncio
import os
import re
import time
from collections.abc import Callable

import httpx

from src.core.errors import DownloadError
from src.engine.http.resume import Segment

SAVE_EVERY = 1.0
ATTEMPTS = 3  # tries per segment before the download fails
BACKOFF = 0.5  # seconds, times the attempt number
_CONTENT_RANGE = re.compile(r"bytes (\d+)-(\d+)/(?:\d+|\*)")


class NoRanges(Exception):
    """The server answered a range request with something other than 206."""


def split(size: int, n: int) -> list[Segment]:
    """`size` bytes cut into up to `n` contiguous ranges."""
    n = max(1, min(n, size))
    step, extra = divmod(size, n)
    segments, start = [], 0
    for i in range(n):
        length = step + (1 if i < extra else 0)
        segments.append(Segment(start, start + length - 1))
        start += length
    return segments


def pwrite_all(fd: int, data: bytes, offset: int) -> None:
    view = memoryview(data)
    while view:
        n = os.pwrite(fd, view, offset)
        view, offset = view[n:], offset + n


def if_range(etag: str | None, last_modified: str | None) -> str | None:
    """The validator for `If-Range`: a strong ETag, else Last-Modified."""
    if etag and not etag.startswith("W/"):
        return etag
    return last_modified


async def download_segments(
    client: httpx.AsyncClient,
    url: str,
    fd: int,
    segments: list[Segment],
    headers: dict[str, str],
    on_progress: Callable[[], None],
    save: Callable[[], None],
    validator: str | None = None,
) -> None:
    """Fetch every unfinished range in parallel with positioned writes. Calls `save` (after an fsync of the
    data) at most once a second and when it stops, however it stops. With a `validator` (ETag or
    Last-Modified) the ranges are conditional, so a file that changed is noticed, not mixed in."""
    last_save = time.monotonic()

    def checkpoint() -> None:
        os.fsync(fd)  # the record must never claim more than is on disk
        save()

    def tick() -> None:
        nonlocal last_save
        on_progress()
        if time.monotonic() - last_save >= SAVE_EVERY:
            last_save = time.monotonic()
            checkpoint()

    async def fetch(seg: Segment) -> None:
        start = seg.start + seg.done
        request = {**headers, "Range": f"bytes={start}-{seg.end}"}
        if validator:
            request["If-Range"] = validator
        async with client.stream("GET", url, headers=request) as resp:
            if resp.status_code == 200:
                if validator and validator not in (resp.headers.get("etag"), resp.headers.get("last-modified")):
                    raise DownloadError("the file changed on the server during the download; run again")
                raise NoRanges
            if resp.status_code != 206:
                resp.raise_for_status()
                raise NoRanges
            m = _CONTENT_RANGE.fullmatch(resp.headers.get("content-range", ""))
            if m is None or int(m.group(1)) != start:
                raise DownloadError(f"server sent the wrong byte range for bytes={start}-{seg.end}")
            async for chunk in resp.aiter_raw():
                chunk = chunk[: seg.length - seg.done]
                pwrite_all(fd, chunk, seg.start + seg.done)
                seg.done += len(chunk)
                tick()
                if seg.done >= seg.length:
                    break
        if seg.done < seg.length:
            raise httpx.ReadError(f"connection closed early at {seg.start + seg.done}")

    async def worker(seg: Segment) -> None:
        for attempt in range(1, ATTEMPTS + 1):
            if seg.done >= seg.length:
                return
            try:
                await fetch(seg)
                return
            except (httpx.TransportError, httpx.HTTPStatusError) as e:
                transient = isinstance(e, httpx.TransportError) or e.response.status_code >= 500
                if not transient or attempt == ATTEMPTS:
                    raise
            await asyncio.sleep(BACKOFF * attempt)

    try:
        try:
            async with asyncio.TaskGroup() as tg:
                for seg in segments:
                    tg.create_task(worker(seg))
        except ExceptionGroup as eg:
            raise eg.exceptions[0] from None
    finally:
        checkpoint()
