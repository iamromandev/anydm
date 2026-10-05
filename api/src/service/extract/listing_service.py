"""A playlist's videos, streamed as the site pages through them (v0.5 part 1).

yt-dlp pages through a listing synchronously, about 90 videos a second on
YouTube, so a 5,000-video channel takes a minute. The listing runs in its own
thread and feeds a queue; this side sends what has arrived in batches, so the
picker shows the first videos at once and the rest as they come. Nothing is
kept: the browser holds the list.
"""

from __future__ import annotations

import asyncio
import threading
from collections.abc import AsyncGenerator, Sequence
from dataclasses import asdict
from typing import Any, Protocol

from loguru import logger

from src.core.base import BaseService
from src.core.error import Error
from src.data.type import DownloadStatus
from src.lib.site import error as site_error
from src.lib.site.client import PlaylistEntry, SiteClient

#: The most one listing sends. A channel past it is taken in slices.
LISTING_LIMIT = 10_000
#: The most videos one ``entries`` frame carries.
BATCH_SIZE = 100
#: How long the site may go without a new video before the listing gives up.
STALL_S = 60.0

#: What the picker calls a video some download already holds.
_HAVE = {
    DownloadStatus.COMPLETE: "complete",
    DownloadStatus.SEEDING: "complete",
    DownloadStatus.FAILED: "failed",
}

Frame = tuple[str, Any]


class HeldLookup(Protocol):
    """The one thing a listing asks of the downloads. ``DownloadDatabaseRepo`` answers it."""

    async def statuses_by_ref(self, provider: str, ref_ids: Sequence[str]) -> dict[str, DownloadStatus]: ...


class _End:
    """The listing thread is done."""


def _have(status: DownloadStatus | None) -> str | None:
    if status is None:
        return None
    return _HAVE.get(status, "queued")


def _entry_json(entry: PlaylistEntry, status: DownloadStatus | None) -> dict[str, Any]:
    data = asdict(entry)
    del data["extractor"]
    data["have"] = _have(status)
    return data


class ListingService(BaseService):
    def __init__(self, client: SiteClient, repo: HeldLookup, *, stall_s: float = STALL_S) -> None:
        super().__init__()
        self._client = client
        self._repo = repo
        self._stall_s = stall_s

    async def frames(self, url: str, *, limit: int = LISTING_LIMIT) -> AsyncGenerator[Frame]:
        """``entries`` frames of up to ``BATCH_SIZE`` videos, then ``done`` or ``failed``.

        Closing this iterator, which is what a client going away does, asks
        the thread to stop at its next video, so a closed picker doesn't
        leave a listing running against the site.
        """
        loop = asyncio.get_running_loop()
        queue: asyncio.Queue[PlaylistEntry | Error | _End] = asyncio.Queue()
        stop = threading.Event()

        def put(item: PlaylistEntry | Error | _End) -> None:
            loop.call_soon_threadsafe(queue.put_nowait, item)

        def run() -> None:
            try:
                for entry in self._client.list_entries(url, limit=limit, should_stop=stop.is_set):
                    put(entry)
            except Error as exc:
                put(exc)
            except Exception as exc:  # a bug, not a site: the stream still ends
                logger.exception("ListingService|{}: {}", url, exc)
                put(site_error.extraction_failed(str(exc)))
            finally:
                put(_End())

        # A daemon thread of its own, not the default executor: downloads use
        # that pool, and a listing may take a minute.
        threading.Thread(target=run, name="playlist-listing", daemon=True).start()
        count = 0
        try:
            while True:
                item: PlaylistEntry | Error | _End | None
                try:
                    item = await asyncio.wait_for(queue.get(), timeout=self._stall_s)
                except TimeoutError:
                    yield "failed", site_error.extraction_failed("The site stopped answering").to_json()
                    return
                batch: list[PlaylistEntry] = []
                while isinstance(item, PlaylistEntry):
                    batch.append(item)
                    if len(batch) == BATCH_SIZE or queue.empty():
                        item = None
                        break
                    item = queue.get_nowait()
                if batch:
                    count += len(batch)
                    yield "entries", await self._described(batch)
                if isinstance(item, Error):
                    yield "failed", item.to_json()
                    return
                if isinstance(item, _End):
                    yield "done", {"count": count}
                    return
        finally:
            stop.set()

    async def _described(self, batch: list[PlaylistEntry]) -> list[dict[str, Any]]:
        """The batch as JSON, each video marked with what a download already holds."""
        held: dict[tuple[str, str], DownloadStatus] = {}
        for extractor in {entry.extractor for entry in batch if entry.extractor}:
            ids = [entry.id for entry in batch if entry.extractor == extractor]
            for video_id, status in (await self._repo.statuses_by_ref(extractor, ids)).items():
                held[(extractor, video_id)] = status
        return [_entry_json(entry, held.get((entry.extractor, entry.id))) for entry in batch]
