"""Torrents, as this API's tasks.

The engine owns the transfer; this service owns the row. It resolves what a
magnet contains, creates the task and its file rows, and translates the control
verbs. Progress is not its job — ``torrent_monitor.py`` does that.
"""

from __future__ import annotations

from pathlib import Path

from src.core.base import BaseService
from src.core.error import Error
from src.data.repo.download.interface import TaskRepo, TorrentFileRepo
from src.data.schema.download import TorrentFileSchema, TorrentResolveResponse
from src.lib.event import EventHub
from src.lib.torrent.protocol import TorrentClient
from src.lib.torrent.source import parse_source


class TorrentService(BaseService):
    def __init__(
        self,
        repo: TaskRepo,
        file_repo: TorrentFileRepo,
        client: TorrentClient,
        hub: EventHub,
        torrent_root: Path,
        enabled: bool,
    ) -> None:
        super().__init__()
        self._repo = repo
        self._file_repo = file_repo
        self._client = client
        self._hub = hub
        self._root = torrent_root
        self._enabled = enabled

    async def resolve(self, raw: str) -> TorrentResolveResponse:
        """What this magnet contains, without downloading any of it.

        Nothing is written. An abandoned magnet therefore leaves no task to
        clean up, which is the whole reason resolving precedes enqueueing.
        """
        self._require_enabled()
        source = parse_source(raw)
        details = await self._client.resolve(source)

        return TorrentResolveResponse(
            info_hash=details.info_hash,
            title=details.name,
            total_bytes=sum(file.size_bytes for file in details.files),
            files=[
                TorrentFileSchema(
                    index=file.index,
                    path=file.path,
                    size_bytes=file.size_bytes,
                    # Everything is offered ticked; the picker is a way to
                    # remove files, not a puzzle to solve before downloading.
                    selected=True,
                )
                for file in details.files
            ],
        )

    def _require_enabled(self) -> None:
        if not self._enabled:
            raise Error.service_unavailable(message="Torrent support is disabled")
