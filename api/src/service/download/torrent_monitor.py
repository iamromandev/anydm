"""One background loop that mirrors the torrent engine onto task rows.

It runs beside the worker pool and deliberately not inside it. rqbit performs
the transfer, so a torrent must never occupy a ``DOWNLOAD_WORKERS`` slot — a
handful of torrents would otherwise starve every YouTube and direct download.

rqbit has no push API, so this polls. The tick interval is what bounds how
fresh progress is; at the default it matches the cadence the existing progress
flush already delivers.
"""

from __future__ import annotations

import asyncio
import contextlib
from typing import Any

from loguru import logger

from src.core.common import now
from src.core.error import Error
from src.data.repo.download.interface import FileRepo, TaskRepo
from src.data.schema.download import TaskSchema
from src.data.type import TaskStatus
from src.lib.event import EventHub
from src.lib.torrent.mapping import progress_percent, status_for
from src.lib.torrent.protocol import TorrentClient, TorrentProgress
from src.lib.torrent.source import parse_source


class TorrentMonitor:
    def __init__(
        self,
        repo: TaskRepo,
        file_repo: FileRepo,
        client: TorrentClient,
        hub: EventHub,
        poll_ms: int,
        torrent_root: str,
        enabled: bool,
        download_limit_bps: int = 0,
        upload_limit_bps: int = 0,
    ) -> None:
        self._repo = repo
        self._file_repo = file_repo
        self._client = client
        self._hub = hub
        self._poll_s = poll_ms / 1000
        self._root = torrent_root
        self._enabled = enabled
        self._task: asyncio.Task[None] | None = None
        #: Reconciliation is a startup job, but the engine may not be up yet at
        #: startup. It therefore runs on the first tick that reaches the engine,
        #: not on a clock.
        self._reconciled = False
        self._download_limit = download_limit_bps
        self._upload_limit = upload_limit_bps
        #: rqbit keeps its limits in memory, so a restart forgets them. Cleared
        #: whenever the engine stops answering, which is how a restart looks
        #: from here, so the next tick that reaches it pushes them again.
        self._limits_pushed = False

    @property
    def _tag(self) -> str:
        return self.__class__.__name__

    async def start(self) -> None:
        if not self._enabled:
            logger.info("{}|torrents are disabled; not starting", self._tag)
            return
        # A readiness probe that reports rather than blocks. The engine is its
        # own container and may still be starting, and refusing to boot the API
        # over that would take YouTube and direct downloads down with it.
        if not await self._client.ping():
            logger.warning(
                "{}|torrent engine is not answering yet; torrents stay dark until it does",
                self._tag,
            )
        self._task = asyncio.create_task(self._run(), name="torrent-monitor")

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
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception:
                # One bad tick must not end the loop: the next one may well
                # succeed, and a dead monitor looks exactly like a stuck
                # download from the browser.
                logger.exception("{}|tick failed", self._tag)
            await asyncio.sleep(self._poll_s)

    async def tick(self) -> None:
        """One pass. Public so tests drive it directly rather than by clock."""
        try:
            samples = await self._client.list_progress()
        except Error as error:
            # An unreachable engine is almost always a restart. Rows are left
            # exactly as they are rather than marked failed.
            logger.warning("{}|engine unreachable: {}", self._tag, error.message)
            self._limits_pushed = False
            return

        if not self._limits_pushed:
            await self._push_limits()

        by_hash = {sample.info_hash: sample for sample in samples if sample.info_hash}
        rows = await self._repo.torrents_to_watch()

        if not self._reconciled:
            await self._reconcile(rows, by_hash)
            self._reconciled = True

        for row in rows:
            sample = by_hash.get(row.info_hash or "")
            if sample is not None:
                await self._apply(row, sample)

    async def _push_limits(self) -> None:
        """Send the caps even when both are unlimited, to clear a stale one.

        A refusal is logged once and not retried every tick: the engine
        answered, so trying again a second later would get the same answer.
        """
        self._limits_pushed = True
        try:
            await self._client.set_rate_limits(
                download_bps=self._download_limit, upload_bps=self._upload_limit
            )
        except Error as error:
            logger.warning("{}|engine refused the rate limits: {}", self._tag, error.message)

    async def _reconcile(self, rows: list[Any], by_hash: dict[str, TorrentProgress]) -> None:
        """Make the engine's session agree with the database, once.

        A row the engine still knows is adopted as-is. A row it has lost is
        re-added from the stored source and the stored selection. A torrent the
        engine has and the database does not is logged and left alone: it may
        have been added outside this service, and deleting someone's data on a
        mismatch is not acceptable.
        """
        for row in rows:
            if (row.info_hash or "") in by_hash:
                continue
            try:
                await self._client.add(
                    parse_source(row.source_url),
                    only_files=await self._file_repo.selected_indexes(row.id),
                    output_folder=self._root,
                )
                logger.info("{}|re-added lost torrent {}", self._tag, row.info_hash)
            except Error as error:
                logger.warning(
                    "{}|could not re-add torrent {}: {}", self._tag, row.info_hash, error.message
                )

        known = {row.info_hash for row in rows}
        for info_hash in by_hash.keys() - known:
            logger.info("{}|engine holds an untracked torrent {}", self._tag, info_hash)

    async def _apply(self, row: Any, sample: TorrentProgress) -> None:
        status = status_for(sample, row.status)
        fields: dict[str, Any] = {
            "status": status,
            "progress": progress_percent(sample.progress_bytes, sample.total_bytes),
            "downloaded_bytes": sample.progress_bytes,
            "total_bytes": sample.total_bytes or None,
            "speed_bps": sample.download_bps,
            "uploaded_bytes": sample.uploaded_bytes,
            "upload_speed_bps": sample.upload_bps,
            "peers_connected": sample.peers_connected,
            "eta_seconds": sample.eta_seconds,
        }
        if status == TaskStatus.FAILED and sample.error:
            fields["error"] = sample.error
        if status in (TaskStatus.SEEDING, TaskStatus.COMPLETE) and row.completed_at is None:
            fields["completed_at"] = now()

        # Only what actually moved is written. The publish below happens every
        # tick regardless, which is what keeps the browser live without the
        # database absorbing the full poll rate.
        changed = {name: value for name, value in fields.items() if getattr(row, name) != value}
        for name, value in changed.items():
            setattr(row, name, value)
        if changed:
            await row.save(update_fields=list(changed))

        await self._file_repo.flush_progress(row.id, sample.file_progress)
        self._hub.publish("task", TaskSchema.model_validate(row).to_json())
