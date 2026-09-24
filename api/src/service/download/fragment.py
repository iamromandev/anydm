"""HLS, DASH and the rest: the parts only yt-dlp's downloader can fetch.

The segmented engine keeps every plain HTTP(S) part. This runs yt-dlp's
blocking download in a thread, and makes it look like the engine to the
worker:

- the same ``AggregateSample`` every flush interval
- ``Stopped`` for a pause or a cancel
- the bytes on disk when it is done
"""

from __future__ import annotations

import asyncio
import threading
from collections.abc import Awaitable, Callable
from pathlib import Path

from src.lib.site.client import DownloadStopped, FormatProgress, SiteClient
from src.service.download.downloader import Stopped
from src.service.download.progress import AggregateSample
from src.service.download.rate_limit import Limiter


def fragment_limits(cap_bps: int, workers: int, segments: int) -> tuple[int, int]:
    """Fragments at a time, and yt-dlp's per-download rate, for the pool's settings.

    - **Without a cap:** as many fragments at once as the engine opens
      segments.
    - **With a cap:** one at a time, because yt-dlp's limit does not hold
      across parallel fragments. Each download gets an equal share, so the
      downloads on this path cannot exceed the cap between them.
    """
    if cap_bps <= 0:
        return segments, 0
    return 1, max(1, cap_bps // workers)


def _sample(progress: FormatProgress) -> AggregateSample:
    total = progress.total_bytes
    return AggregateSample(
        downloaded_bytes=progress.downloaded_bytes,
        total_bytes=total,
        progress=min(100, progress.downloaded_bytes * 100 // total) if total else 0,
        speed_bps=progress.speed_bps or 0,
        eta_seconds=progress.eta_seconds,
        segments=(),
    )


class FragmentDownloader:
    def __init__(
        self,
        client: SiteClient,
        *,
        concurrency: int,
        rate_bps: int,
        limiter: Limiter,
        poll_s: float,
    ) -> None:
        self._client = client
        self._concurrency = concurrency
        self._rate_bps = rate_bps
        self._limiter = limiter
        self._poll_s = poll_s

    async def fetch(
        self,
        page_url: str,
        format_id: str,
        destination: Path,
        *,
        on_sample: Callable[[AggregateSample], Awaitable[None]],
        should_stop: Callable[[], bool],
    ) -> int:
        latest: list[FormatProgress] = []
        shutdown = threading.Event()
        charged = 0

        def on_progress(progress: FormatProgress) -> None:
            # Called from yt-dlp's threads. One slot, replaced whole, so the
            # loop below always reads a complete record.
            latest[:] = [progress]

        def stopping() -> bool:
            # A thread cannot be interrupted. ``shutdown`` is how a cancelled
            # worker makes it end at its next progress call.
            return shutdown.is_set() or should_stop()

        async def report() -> None:
            nonlocal charged
            # Once a stop is asked, the pause or cancel has written the row's
            # last numbers, and the engine reports nothing after it either.
            if not latest or should_stop():
                return
            progress = latest[0]
            fresh = progress.downloaded_bytes - charged
            if fresh > 0:
                # yt-dlp's reads cannot pass through the shared limiter, so its
                # bytes are charged after the fact. HTTP downloads running
                # alongside then slow down to leave room.
                charged = progress.downloaded_bytes
                await self._limiter.acquire(fresh)
            await on_sample(_sample(progress))

        download = asyncio.ensure_future(
            asyncio.to_thread(
                self._client.download_format,
                page_url,
                format_id,
                destination,
                concurrency=self._concurrency,
                rate_bps=self._rate_bps,
                on_progress=on_progress,
                should_stop=stopping,
            )
        )
        # Retrieves the thread's outcome even when nobody awaits it any more,
        # as after a cancellation, so asyncio has no unretrieved error to log.
        download.add_done_callback(lambda done: done.cancelled() or done.exception())
        try:
            while not download.done():
                await asyncio.wait({download}, timeout=self._poll_s)
                await report()
            download.result()
        except DownloadStopped as stop:
            raise Stopped from stop
        finally:
            shutdown.set()
        return destination.stat().st_size
