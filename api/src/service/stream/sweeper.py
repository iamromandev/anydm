"""Background loop that removes stream sessions nobody has touched in a while.

Mirrors ``TorrentMonitor`` (``api/src/service/download/torrent_monitor.py``):
same start/stop/`_run` shape, same "one bad tick must not end the loop" guard.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from collections.abc import Callable

from loguru import logger

from src.service.stream.session import StreamSessionStore
from src.service.stream.stream_service import StreamService


class StreamIdleSweeper:
    def __init__(
        self,
        service: StreamService,
        sessions: StreamSessionStore,
        idle_timeout_s: int,
        poll_s: float = 30.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._service = service
        self._sessions = sessions
        self._idle_timeout_s = idle_timeout_s
        self._poll_s = poll_s
        self._clock = clock
        self._task: asyncio.Task[None] | None = None

    @property
    def _tag(self) -> str:
        return self.__class__.__name__

    async def start(self) -> None:
        self._task = asyncio.create_task(self._run(), name="stream-idle-sweeper")

    async def stop(self) -> None:
        if self._task is None:
            return
        self._task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self._task
        self._task = None

    async def _run(self) -> None:
        while True:
            try:
                await self.sweep()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("{}|sweep failed", self._tag)
            await asyncio.sleep(self._poll_s)

    async def sweep(self) -> None:
        """One pass. Public so tests drive it directly rather than by clock."""
        now = self._clock()
        for session in self._sessions.all():
            if session.idle_seconds(now=now) >= self._idle_timeout_s:
                await self._service.stop_session(session.id)
