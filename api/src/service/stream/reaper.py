"""Background loop that deletes rqbit torrents nothing owns any more.

Mirrors ``StreamIdleSweeper``: same start/stop/`_run` shape, same "one bad
tick must not end the loop" guard. Where the sweeper only sees sessions this
process still holds in memory, this asks rqbit directly what it has, so a
torrent orphaned by a lost DELETE call or an API restart still gets reaped.
"""

from __future__ import annotations

import asyncio
import contextlib

from loguru import logger

from src.data.repo.download.interface.task import TaskRepo
from src.lib.torrent.protocol import TorrentClient
from src.service.stream.session import StreamSessionStore


class TorrentReaper:
    def __init__(
        self,
        client: TorrentClient,
        task_repo: TaskRepo,
        sessions: StreamSessionStore,
        poll_s: float = 60.0,
    ) -> None:
        self._client = client
        self._task_repo = task_repo
        self._sessions = sessions
        self._poll_s = poll_s
        self._task: asyncio.Task[None] | None = None

    @property
    def _tag(self) -> str:
        return self.__class__.__name__

    async def start(self) -> None:
        self._task = asyncio.create_task(self._run(), name="torrent-reaper")

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
        live_hashes = {session.info_hash for session in self._sessions.all() if session.info_hash}
        for progress in await self._client.list_progress():
            if progress.info_hash in live_hashes:
                continue
            owner = await self._task_repo.get_one(
                info_hash=progress.info_hash, deleted_at__isnull=True
            )
            if owner is None:
                await self._client.delete(progress.info_hash)
