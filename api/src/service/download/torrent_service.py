"""Torrents, as this API's tasks.

The engine owns the transfer; this service owns the row. It resolves what a
magnet contains, creates the task and its file rows, and translates the control
verbs. Progress is not its job — ``torrent_monitor.py`` does that.
"""

from __future__ import annotations

import mimetypes
import uuid
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from loguru import logger

from src.core.base import BaseService
from src.core.common import now
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.data.repo.download.interface import TaskRepo, TorrentFileRepo
from src.data.schema.download import TaskSchema, TorrentFileSchema, TorrentResolveResponse
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.event import EventHub
from src.lib.torrent.protocol import TorrentClient, TorrentDetails
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

    async def enqueue(self, raw: str, files: Sequence[int]) -> TaskSchema:
        """Start a torrent and record it as a task.

        The engine is asked first. Its answer carries the info hash, the real
        file list and the folder it chose, and a row created before that would
        be a guess at all three.
        """
        self._require_enabled()
        source = parse_source(raw)

        # Resolving before adding costs one extra round trip and buys the
        # rejection below: a selection naming a file the torrent does not have
        # would otherwise be a silently empty download.
        details = await self._client.resolve(source)
        selected = self._validated_selection(details, files)

        await self._client.add(
            source,
            only_files=sorted(selected) if len(selected) != len(details.files) else [],
            output_folder=str(self._root),
        )

        task = await self._repo.create(
            # A base64 .torrent must never land in this column: it is what
            # reconciliation re-adds the torrent from, and an info-hash magnet
            # is both small and re-addable.
            source_url=raw.strip() if not source.is_blob else f"magnet:?xt=urn:btih:{details.info_hash}",
            platform=Platform.TORRENT,
            video_id=None,
            # No preset applies to a torrent; ``kind`` is what says so.
            preset=Preset.BEST,
            kind=Kind.TORRENT,
            title=details.name,
            filename=details.name,
            mime_type=None,
            video_itag=None,
            audio_itag=None,
            info_hash=details.info_hash,
            file_path=details.output_folder or str(self._root),
            total_bytes=sum(file.size_bytes for file in details.files if file.index in selected),
            status=TaskStatus.PENDING,
            progress=0,
        )

        await self._file_repo.replace(
            task.id,
            [
                (file.index, file.path, file.size_bytes, file.index in selected)
                for file in details.files
            ],
        )
        return self._published(task)

    def _validated_selection(self, details: TorrentDetails, files: Sequence[int]) -> set[int]:
        """The chosen indexes, or every index when nothing was chosen."""
        available = {file.index for file in details.files}
        if not files:
            return available

        unknown = sorted(set(files) - available)
        if unknown:
            raise Error.create(
                code=Code.UNPROCESSABLE_ENTITY,
                message=f"Torrent has no file at index {', '.join(str(i) for i in unknown)}",
                error_type=ErrorType.UNPROCESSABLE_ENTITY,
            )
        return set(files)

    def _published(self, task: Any) -> TaskSchema:
        """Serialise, announce, and hand back — as ``DownloadService`` does.

        Publishing here is what makes a torrent appear in every open browser
        the moment it is added, rather than on the monitor's next tick.
        """
        schema = TaskSchema.model_validate(task)
        self._hub.publish("task", schema.to_json())
        return schema

    async def pause(self, task_id: uuid.UUID) -> TaskSchema:
        task = await self._require(task_id)
        if task.status not in (TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.SEEDING):
            raise Error.conflict(message=f"Cannot pause a task that is {task.status.value}")

        await self._client.pause(task.info_hash or "")
        task.status = TaskStatus.PAUSED
        task.speed_bps = 0
        task.eta_seconds = None
        await task.save(update_fields=["status", "speed_bps", "eta_seconds"])
        return self._published(task)

    async def resume(self, task_id: uuid.UUID) -> TaskSchema:
        task = await self._require(task_id)
        if task.status not in (TaskStatus.PAUSED, TaskStatus.FAILED):
            raise Error.conflict(message=f"Cannot resume a task that is {task.status.value}")

        await self._client.start(task.info_hash or "")
        # ``downloading`` rather than ``pending``: there is no queue to wait in,
        # the engine is moving bytes the moment it is started. The next monitor
        # tick corrects this to whatever the engine actually reports.
        task.status = TaskStatus.DOWNLOADING
        task.error = None
        task.error_code = None
        await task.save(update_fields=["status", "error", "error_code"])
        return self._published(task)

    async def stop_seeding(self, task_id: uuid.UUID) -> TaskSchema:
        """Stop sharing, keep the files.

        Paused in the engine rather than forgotten, so seeding can be started
        again later without re-adding the magnet. ``complete`` is the status a
        torrent can only reach this way.
        """
        task = await self._require(task_id)
        if task.status != TaskStatus.SEEDING:
            raise Error.conflict(message=f"Task is {task.status.value}, not seeding")

        await self._client.pause(task.info_hash or "")
        task.status = TaskStatus.COMPLETE
        task.speed_bps = 0
        task.eta_seconds = None
        await task.save(update_fields=["status", "speed_bps", "eta_seconds"])
        return self._published(task)

    async def cancel(self, task_id: uuid.UUID) -> None:
        """Remove the torrent, its files, and the row.

        The engine's delete removes the files, which is what cancelling a
        download already means here. An engine that cannot be reached does not
        block it: the person asked for this to be gone, and a stranded torrent
        is a smaller problem than a row that refuses to disappear.
        """
        task = await self._require(task_id)
        try:
            await self._client.delete(task.info_hash or "")
        except Error as error:
            logger.warning("{}|engine delete failed for {}: {}", self._tag, task.id, error.message)

        task.status = TaskStatus.CANCELED
        task.deleted_at = now()
        task.speed_bps = 0
        task.eta_seconds = None
        await task.save(update_fields=["status", "deleted_at", "speed_bps", "eta_seconds"])
        self._published(task)

    async def resolve_file(self, task_id: uuid.UUID, index: int) -> tuple[Path, str, str]:
        """One finished file out of a torrent, by its index.

        409 rather than 404 while the torrent is still running, for the same
        reason ``DownloadService.resolve_file`` does it: the resource will
        exist, just not yet, and a polling client has to tell "wait" from
        "never".
        """
        task = await self._require(task_id)
        if task.status not in (TaskStatus.SEEDING, TaskStatus.COMPLETE):
            raise Error.conflict(message=f"Task is {task.status.value}, not complete")

        rows = await self._file_repo.list_for(task_id)
        row = next((candidate for candidate in rows if candidate.index == index), None)
        if row is None:
            raise Error.not_found(message=f"Torrent has no file at index {index}")
        if not row.selected:
            raise Error.conflict(message=f"File {index} was not selected for download")

        folder = Path(task.file_path or str(self._root)).resolve()
        path = (folder / row.path).resolve()
        # A torrent's file names are written by a stranger. Containment is
        # checked against the resolved folder, not by inspecting the string.
        if not path.is_relative_to(folder):
            raise Error.not_found(message="File is not inside the torrent's folder")
        if not path.is_file():
            raise Error.not_found(message="File is no longer on disk")

        media_type, _ = mimetypes.guess_type(path.name)
        return path, path.name, media_type or "application/octet-stream"

    async def _require(self, task_id: uuid.UUID) -> Any:
        self._require_enabled()
        task = await self._repo.get_active_by_id(task_id)
        if task is None:
            raise Error.not_found(message="Task not found")
        return task

    def _require_enabled(self) -> None:
        if not self._enabled:
            raise Error.service_unavailable(message="Torrent support is disabled")
