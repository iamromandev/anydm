"""Background loop that turns what downloads did into ``audit.traffic`` and ``audit.speed`` (#362).

It reads rather than being told. Bytes and status changes are already written to
each download's row by the worker pool and the torrent monitor, so the recorder
looks at those rows every few seconds and adds up what changed since it last
looked. Nothing on the hot path of a transfer knows it exists, and a tick is the
batch: one upsert per day, kind and provider, not one write per progress frame.

What it can miss: the bytes a download moved and left every moving status
within one tick, unless it was created in that window. A restart loses at most
one tick's, because stopping runs a last tick.
"""

from __future__ import annotations

import asyncio
import contextlib
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta

from loguru import logger

from src.core.common import now
from src.data.repo.stat import DownloadSnapshot, StatRepo, TrafficDelta
from src.data.type import DONE_STATUSES, DownloadStatus, SourceKind
from src.service.download.live import LiveStats

#: How long hourly speeds are kept.
SPEED_RETENTION = timedelta(days=30)


@dataclass(slots=True)
class _Seen:
    """What a download looked like the last time it was read."""

    downloaded_size: int
    uploaded_size: int
    finished: bool
    failed: bool
    removed: bool


class TrafficRecorder:
    def __init__(
        self,
        repo: StatRepo,
        live: LiveStats,
        poll_s: float = 10.0,
        clock: Callable[[], datetime] = now,
    ) -> None:
        self._repo = repo
        self._live = live
        self._poll_s = poll_s
        self._clock = clock
        self._task: asyncio.Task[None] | None = None
        self._seen: dict[uuid.UUID, _Seen] = {}
        #: When the last tick read the rows: a download created after it has no bytes counted yet.
        self._read_at = clock()
        #: Whether a tick has read every failed download once: after that, one new to the recorder failed just now.
        self._primed = False
        self._pruned_hour: datetime | None = None

    @property
    def _tag(self) -> str:
        return self.__class__.__name__

    async def start(self) -> None:
        self._read_at = self._clock()
        self._task = asyncio.create_task(self._run(), name="traffic-recorder")

    async def stop(self) -> None:
        if self._task is None:
            return
        self._task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self._task
        self._task = None
        try:
            await self.tick()
        except Exception:
            logger.exception("{}|last tick failed", self._tag)

    async def _run(self) -> None:
        while True:
            await asyncio.sleep(self._poll_s)
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("{}|tick failed", self._tag)

    async def tick(self) -> None:
        """One pass. Public so tests drive it directly rather than by clock."""
        at = self._clock()
        await self._count(at)
        await self._sample(at)

    async def _count(self, at: datetime) -> None:
        snapshots = await self._repo.snapshots(self._seen.keys(), self._read_at)
        deltas: dict[tuple[SourceKind, uuid.UUID], TrafficDelta] = {}
        seen: dict[uuid.UUID, _Seen] = {}
        for snapshot in snapshots:
            before = self._seen.get(snapshot.id) or self._first_sight(snapshot)
            key = (snapshot.kind, snapshot.provider_id)
            deltas[key] = deltas.get(key, TrafficDelta()) + _delta(before, snapshot)
            seen[snapshot.id] = _Seen(
                downloaded_size=snapshot.downloaded_size,
                uploaded_size=snapshot.uploaded_size,
                finished=snapshot.status in DONE_STATUSES,
                failed=snapshot.status == DownloadStatus.FAILED,
                removed=snapshot.removed,
            )

        day = at.date()
        for (kind, provider_id), delta in deltas.items():
            if delta:
                await self._repo.add_traffic(day, kind, provider_id, delta)

        # Only after every write: a failed one is read again next tick instead of being lost.
        self._read_at = at
        self._primed = True
        # What the query no longer returns is gone (deleted outright), so only what it returned is kept.
        self._seen = seen
        for snapshot in snapshots:
            if snapshot.removed or snapshot.status == DownloadStatus.COMPLETED:
                # At rest for good: nothing more to add, and nothing will read it again.
                self._seen.pop(snapshot.id, None)

    def _first_sight(self, snapshot: DownloadSnapshot) -> _Seen:
        """A download new to the recorder, and what of it was counted before.

        One created since the last read starts from zero. An older one has been counted, or ran before this
        process started, so its bytes so far are the starting line; what happened to it since the last read
        (completed, removed) is news, and so is a failure once every failed download has been read.
        """
        if snapshot.created_at >= self._read_at:
            return _Seen(0, 0, False, False, False)
        return _Seen(
            downloaded_size=snapshot.downloaded_size,
            uploaded_size=snapshot.uploaded_size,
            finished=snapshot.status in DONE_STATUSES and not _since(snapshot.completed_at, self._read_at),
            failed=snapshot.status == DownloadStatus.FAILED and not self._primed,
            removed=snapshot.removed and not _since(snapshot.deleted_at, self._read_at),
        )

    async def _sample(self, at: datetime) -> None:
        """One speed reading, only while something is moving: a quiet hour has no row."""
        hour = at.replace(minute=0, second=0, microsecond=0)
        total = sum(self._live.speeds().values())
        if total > 0:
            await self._repo.add_speed(hour, total)
        if self._pruned_hour != hour:
            await self._repo.prune_speed(hour - SPEED_RETENTION)
            self._pruned_hour = hour


def _since(at: datetime | None, read_at: datetime) -> bool:
    return at is not None and at >= read_at


def _delta(before: _Seen, current: DownloadSnapshot) -> TrafficDelta:
    return TrafficDelta(
        # A download that starts over reads lower than before; that is not negative traffic.
        downloaded_size=max(0, current.downloaded_size - before.downloaded_size),
        uploaded_size=max(0, current.uploaded_size - before.uploaded_size),
        finished=int(current.status in DONE_STATUSES and not before.finished),
        failed=int(current.status == DownloadStatus.FAILED and not before.failed),
        removed=int(current.removed and not before.removed),
    )
