"""Background loop that tells open browsers how much disk is left.

Mirrors ``TorrentReaper``'s start/stop/``_run`` shape. A browser also gets one
``disk`` frame when it connects to ``/download/events``; this keeps it current
afterwards, without the UI polling.
"""

from __future__ import annotations

import asyncio
import contextlib
from dataclasses import asdict

from loguru import logger

from src.lib.event import EventHub
from src.service.download.disk import DiskGuard


class DiskMonitor:
    def __init__(self, guard: DiskGuard, hub: EventHub, poll_s: float = 30.0) -> None:
        self._guard = guard
        self._hub = hub
        self._poll_s = poll_s
        self._task: asyncio.Task[None] | None = None

    @property
    def _tag(self) -> str:
        return self.__class__.__name__

    async def start(self) -> None:
        self._task = asyncio.create_task(self._run(), name="disk-monitor")

    async def stop(self) -> None:
        if self._task is None:
            return
        self._task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self._task
        self._task = None

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
        """One report. Public so tests drive it directly rather than by clock.

        Every tick while anyone is listening, not only while a task is active:
        seeding torrents and other programs fill disks too, and the read is a
        single ``statvfs``.
        """
        if self._hub.subscriber_count() == 0:
            return
        usage = self._guard.usage()
        if usage is not None:
            self._hub.publish("disk", asdict(usage))
