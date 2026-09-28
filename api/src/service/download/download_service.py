from __future__ import annotations

import asyncio
import shutil
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, get_args

from loguru import logger

from src.core.base import BaseService
from src.core.common import now
from src.core.error import Error
from src.core.success import Meta
from src.data.repo.download.interface import PositionRepo, SegmentRepo, TaskRepo
from src.data.schema.download import PlaylistDownloadRequest, PositionSchema, TaskSchema, TaskSummarySchema
from src.data.type import TASK_GROUPS, Kind, Platform, Preset, TaskSort, TaskStatus
from src.lib.event import EventHub
from src.lib.folder import named_folder
from src.lib.media.sidecar import Sidecar, SidecarSource, folder_listing, match_sidecars
from src.lib.site import error as site_error
from src.lib.site.client import SiteClient
from src.lib.site.filename import number_prefix, safe_filename
from src.lib.site.format import select_plan
from src.service.download.control import DownloadControl
from src.service.download.direct import ensure_fetchable, filename_from_url
from src.service.download.disk import DiskGuard
from src.service.download.download_worker import remove_task_files
from src.service.download.group_totals import GroupTotals
from src.service.download.torrent_service import TorrentService


#: Which rows each bulk action applies to. Seeding is in the pause set
#: because only a torrent can be seeding and the engine accepts pausing one;
#: failed is in both the resume set and the clear set, because a failure is
#: equally "try again" and "give up on this".
def remove_group_video_files(video: Path) -> None:
    """A group video's file and the subtitle files beside it, never its folder (v0.5).

    Its subtitles share its stem: ``02_Talk_720p.en.vtt`` beside ``02_Talk_720p.mp4``.
    """
    if not video.parent.is_dir():
        return
    prefix = f"{video.stem}."
    for path in video.parent.iterdir():
        if path == video or (path.name.startswith(prefix) and path.suffix in (".vtt", ".srt")):
            path.unlink(missing_ok=True)


#: The most videos one playlist add takes; the picker stops there too.
PLAYLIST_LIMIT = 10_000

BULK_SCOPES: dict[str, frozenset[TaskStatus]] = {
    "pause_all": frozenset(
        {TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.SEEDING}
    ),
    "resume_all": frozenset({TaskStatus.PAUSED, TaskStatus.FAILED}),
    "clear_finished": frozenset({TaskStatus.COMPLETE, TaskStatus.FAILED}),
}


@dataclass(frozen=True, slots=True)
class TorrentPlay:
    """A torrent task still downloading, to play through rqbit's stream (#95)."""

    info_hash: str
    file_index: int


class DownloadService(BaseService):
    def __init__(
        self,
        repo: TaskRepo,
        segment_repo: SegmentRepo,
        client: SiteClient,
        control: DownloadControl,
        hub: EventHub,
        downloads_root: Path,
        torrents: TorrentService,
        disk: DiskGuard | None = None,
        positions: PositionRepo | None = None,
        groups: GroupTotals | None = None,
    ) -> None:
        super().__init__()
        self._repo = repo
        self._segment_repo = segment_repo
        self._client = client
        self._control = control
        self._hub = hub
        self._root = downloads_root
        self._torrents = torrents
        self._disk = disk
        self._positions = positions
        self._groups = groups

    async def _refresh_group(self, task: Any) -> None:
        """Bring a group video's group up to date after a change to it (v0.5)."""
        parent_id = getattr(task, "parent_id", None)
        if self._groups is not None and parent_id is not None:
            await self._groups.refresh(parent_id)

    async def _group_changed(self, group: Any) -> TaskSchema:
        """The group row after its videos changed: its totals, else the row as it is."""
        if self._groups is not None:
            refreshed = await self._groups.refresh(group.id)
            if refreshed is not None:
                return refreshed
        return self._published(group)

    async def _cancel_group(self, group: Any, *, delete_files: bool) -> None:
        """Remove a group and all of its videos (v0.5).

        Keeping the files is accepted in any state: what finished is whole, and
        the unfinished videos' working folders go either way.
        """
        for video in await self._repo.remove_entries(group.id):
            self._control.request_stop(video)
            remove_task_files(self._root, video)
        if delete_files and group.file_path:
            # A folder of 5,000 files shouldn't hold up the API.
            await asyncio.to_thread(shutil.rmtree, self._root / group.file_path, ignore_errors=True)
        group.status = TaskStatus.CANCELED
        group.deleted_at = now()
        group.speed_bps = 0
        await group.save(update_fields=["status", "deleted_at", "speed_bps"])
        self._published(group)

    async def _with_counts(self, schemas: list[TaskSchema]) -> list[TaskSchema]:
        """Each group row with how its videos stand: one query per group on the page."""
        if self._groups is None:
            return schemas
        for schema in schemas:
            if isinstance(schema, TaskSchema) and schema.kind == Kind.PLAYLIST:
                schema.entry_counts = await self._groups.counts(schema.id)
        return schemas

    def _require_space(self, extra_bytes: int | None = None) -> None:
        if self._disk is not None:
            self._disk.require(extra_bytes or 0)

    async def enqueue_media(self, url: str, preset: Preset) -> TaskSchema:
        """Resolve the plan now, move the bytes later, for any site.

        Everything that can fail on the caller's behalf (an unsupported link, a
        private video, a live stream, a preset the site cannot satisfy) fails
        here, as a 4xx they see immediately. What reaches the queue is a
        decision, not a guess.
        """
        info = await self._client.extract(url)
        if info.is_live:
            raise site_error.live_not_supported()
        plan = select_plan(info.formats, preset)
        # Refused before the row exists, so a 507 leaves nothing behind. An
        # estimated size counts here; an unknown one is checked against the
        # minimum alone, and the worker checks again once the probe knows.
        self._require_space(plan.expected_bytes)
        suffix = "" if preset == Preset.MP3 else plan.quality

        task = await self._repo.create(
            source_url=url,
            platform=Platform.SITE,
            extractor=info.extractor,
            video_id=info.id,
            preset=preset,
            kind=plan.kind,
            title=info.title,
            filename=safe_filename(info.title, suffix, plan.extension),
            mime_type=plan.mime_type,
            video_format=plan.video.id if plan.video else None,
            audio_format=plan.audio.id if plan.audio else None,
            # Only an exact size: a progress bar measured against an estimate
            # stalls short of 100 or runs past it. The probe learns the rest.
            total_bytes=None if plan.size_is_estimate else plan.expected_bytes,
            status=TaskStatus.PENDING,
            progress=0,
        )
        # Workers share this process, so a queued task starts in milliseconds
        # rather than on the next poll tick.
        self._control.wake()
        return self._published(task)

    async def enqueue_url(self, url: str) -> TaskSchema:
        """Queue a plain HTTP download — anything that is not a media platform.

        ``preset`` is BEST only because the column is not nullable and no preset
        applies to an arbitrary file; ``kind=FILE`` is what actually says this
        has no quality dimension.
        """
        ensure_fetchable(url)
        # The size is not known until a worker probes the source, so only the
        # minimum can be checked here.
        self._require_space()
        name = filename_from_url(url)
        task = await self._repo.create(
            source_url=url,
            platform=Platform.DIRECT,
            video_id=None,
            preset=Preset.BEST,
            kind=Kind.FILE,
            title=name,
            filename=name,
            mime_type=None,
            video_format=None,
            audio_format=None,
            total_bytes=None,
            status=TaskStatus.PENDING,
            progress=0,
        )
        self._control.wake()
        return self._published(task)

    async def enqueue_playlist(self, request: PlaylistDownloadRequest) -> TaskSchema:
        """A playlist's chosen videos, as one group of videos planned when each starts.

        Nothing is extracted here: 5,000 videos cannot be planned inside one
        request. A video that turns out private, or without a format for the
        preset, fails inside the group rather than at add time.
        """
        if len(request.entries) > PLAYLIST_LIMIT:
            raise site_error.playlist_too_large(len(request.entries))
        self._require_space()
        folder = named_folder(self._root, request.title, request.playlist_id)
        folder.mkdir(parents=True, exist_ok=True)
        kind = Kind.AUDIO if request.preset == Preset.MP3 else Kind.VIDEO
        largest = max(entry.index for entry in request.entries)
        # A listing's extractor is "YoutubeTab"; its videos are "Youtube", the
        # name single downloads and the picker's "already have it" use.
        video_extractor = request.extractor.removesuffix("Tab")
        group = await self._repo.create_group(
            {
                "source_url": request.url,
                "platform": Platform.SITE,
                "extractor": request.extractor,
                "video_id": request.playlist_id,
                "preset": request.preset,
                "kind": Kind.PLAYLIST,
                "title": request.title,
                "status": TaskStatus.PENDING,
                "progress": 0,
                "file_path": str(folder.relative_to(self._root)),
            },
            [
                {
                    "source_url": entry.url,
                    "platform": Platform.SITE,
                    "extractor": video_extractor,
                    "video_id": entry.id,
                    "preset": request.preset,
                    "kind": kind,
                    "title": entry.title or "",
                    "position": entry.index,
                    "status": TaskStatus.PENDING,
                    "progress": 0,
                    "video_format": None,
                    "audio_format": None,
                    # The number now; choosing the formats appends the name.
                    "filename": "" if request.channel_tab else number_prefix(entry.index, largest),
                }
                for entry in request.entries
            ],
        )
        self._control.wake()
        return self._published(group)

    async def list_entries(self, group_id: uuid.UUID, page: int, page_size: int) -> tuple[list[TaskSchema], Meta]:
        """One page of a group's videos, in playlist order."""
        group = await self._require(group_id)
        if group.kind != Kind.PLAYLIST:
            raise Error.not_found(message="Task is not a playlist")
        rows, meta = await self._repo.entries_page(group_id, page=page, page_size=page_size)
        return await self._with_positions([TaskSchema.model_validate(row) for row in rows]), meta

    async def list_tasks(
        self,
        page: int,
        page_size: int,
        group: str = "all",
        sort: str = "-created_at",
    ) -> tuple[list[TaskSchema], Meta]:
        """One page of the list, narrowed to one of the sidebar's groups.

        The group is named rather than spelled out as a list of statuses so
        that the filter and the counts beside it cannot drift: both read
        ``TASK_GROUPS``.
        """
        if group != "all" and group not in TASK_GROUPS:
            raise Error.bad_request(message=f"Unknown group: {group}")

        # Checked here as well as at the route, because this value reaches
        # the database's ORDER BY and a caller inside the process has no
        # FastAPI between it and that.
        if sort not in get_args(TaskSort):
            raise Error.bad_request(message=f"Cannot sort by: {sort}")

        statuses = None if group == "all" else sorted(TASK_GROUPS[group])
        tasks, meta = await self._repo.list_page(
            page=page, page_size=page_size, statuses=statuses, sort=sort
        )
        # Through the torrent service, which adds a torrent's files: one query
        # for the whole page (#107).
        schemas = await self._with_positions(await self._torrents.schemas(tasks))
        return await self._with_counts(schemas), meta

    async def bulk(self, action: str, *, delete_files: bool = False) -> int:
        """Apply one action to every row it makes sense for.

        Which rows those are is decided here rather than by the caller. The
        preconditions already live on ``pause``, ``resume`` and ``cancel``, and
        letting a client name its own set of statuses only invites it to name
        one they refuse — a bulk request that half fails is worse than one that
        cannot be expressed.

        Every row goes through those same three methods, so a torrent is paused
        by the engine and a direct download by the worker, exactly as a single
        action would do it. One row refusing does not end the sweep: a stale
        status is the most likely reason, and the rest of the list should not
        pay for it.
        """
        scope = BULK_SCOPES.get(action)
        if scope is None:
            raise Error.bad_request(message=f"Unknown bulk action: {action}")

        affected = 0
        # A group's videos move in one update rather than a row at a time (v0.5),
        # and each group they belong to is brought up to date once.
        touched: set[uuid.UUID] = set()
        if action == "pause_all":
            running, touched = await self._repo.pause_all_entries()
            for video in running:
                self._control.request_stop(video)
        elif action == "resume_all":
            touched = await self._repo.resume_all_entries()
            self._control.wake()

        # The rest one at a time, as before: standalone tasks and torrents, and
        # for clear_finished whole groups. Never a video out of its group.
        rows = [
            row
            for row in await self._repo.by_statuses(sorted(scope))
            if getattr(row, "parent_id", None) is None
            and (action == "clear_finished" or getattr(row, "kind", None) != Kind.PLAYLIST)
        ]
        for row in rows:
            try:
                if action == "pause_all":
                    await self.pause(row.id)
                elif action == "resume_all":
                    await self.resume(row.id)
                else:
                    # Only a finished row has anything worth keeping; asking to
                    # keep the remains of a failure is refused by ``cancel``.
                    keepable = row.status in (TaskStatus.COMPLETE, TaskStatus.SEEDING)
                    await self.cancel(
                        row.id, delete_files=delete_files or not keepable
                    )
                affected += 1
            except Error as error:
                logger.warning(
                    "{}|bulk {} skipped {}: {}", self._tag, action, row.id, error.message
                )

        for group_id in touched:
            if self._groups is not None:
                await self._groups.refresh(group_id)
            affected += 1
        return affected

    async def summary(self) -> TaskSummarySchema:
        return await self._repo.summary()

    async def get_task(self, task_id: uuid.UUID) -> TaskSchema:
        (schema,) = await self._with_positions([await self._torrents.schema(await self._require(task_id))])
        (schema,) = await self._with_counts([schema])
        return schema

    #: Stopping this close to the end counts as having watched it (#96).
    WATCHED_WITHIN_S = 30.0

    async def save_position(
        self,
        task_id: uuid.UUID,
        file_index: int | None,
        *,
        position_seconds: float,
        duration_seconds: float,
    ) -> PositionSchema:
        """Record where a download was left in the player, so it resumes on any device (#96).

        Stopping within ``WATCHED_WITHIN_S`` of the end marks the file watched
        and clears where to resume, so it opens from the start next time. A
        file once watched stays watched when played again.
        """
        if self._positions is None:
            raise Error.service_unavailable("Saving positions is not configured")
        await self._require(task_id)
        index = file_index or 0
        previous = next(
            (row for row in (await self._positions.list_for_tasks([task_id]))[task_id] if row.file_index == index),
            None,
        )
        near_end = duration_seconds > 0 and duration_seconds - position_seconds <= self.WATCHED_WITHIN_S
        row = await self._positions.save(
            task_id,
            index,
            position_seconds=0.0 if near_end else position_seconds,
            duration_seconds=duration_seconds,
            watched=near_end or bool(previous and previous.watched),
        )
        return PositionSchema.model_validate(row)

    async def _with_positions(self, schemas: list[TaskSchema]) -> list[TaskSchema]:
        """Each task with where its files were left in the player, in one query (#96)."""
        if self._positions is None or not schemas:
            return schemas
        by_task = await self._positions.list_for_tasks([schema.id for schema in schemas])
        for schema in schemas:
            schema.positions = [PositionSchema.model_validate(row) for row in by_task.get(schema.id, [])]
        return schemas

    async def resolve_file(self, task_id: uuid.UUID) -> tuple[Path, str, str]:
        """The finished file for ``task_id``.

        409 rather than 404 while a task is still running: the resource will
        exist, just not yet — which is what the Bun API said for a verifying
        torrent, and what a polling client needs to tell "wait" from "never".

        A torrent goes to the torrent service: its ``file_path`` is a folder,
        and it is done while seeding too (#107).
        """
        task = await self._require(task_id)
        if task.platform == Platform.TORRENT:
            return await self._torrents.resolve_only_file(task_id)

        if task.status != TaskStatus.COMPLETE or not task.file_path:
            raise Error.conflict(message=f"Task is {task.status.value}, not complete")

        path = self._root / task.file_path
        if not path.is_file():
            raise Error.not_found(message="File is no longer on disk")

        return path, task.filename, task.mime_type or "application/octet-stream"

    async def resolve_media_file(
        self, task_id: uuid.UUID, file_index: int | None
    ) -> tuple[Path, str, int | None]:
        """The file Play reads from disk, and its index in a torrent (#94).

        A download's own file, or one of a torrent's: the one asked for, else
        its largest selected media file. The same checks as ``resolve_file``
        apply, so nothing unfinished, missing or outside its folder is played.
        """
        task = await self._require(task_id)
        if task.platform == Platform.TORRENT:
            if file_index is None:
                file_index = await self._torrents.media_file_index(task_id)
            path, filename, _ = await self._torrents.resolve_file(task_id, file_index)
            return path, filename, file_index
        if file_index is not None:
            raise Error.not_found(message="Only a torrent has files by index")
        path, filename, _ = await self.resolve_file(task_id)
        return path, filename, None

    async def subtitle_files(
        self, task_id: uuid.UUID, file_index: int | None
    ) -> list[tuple[Sidecar, SidecarSource]]:
        """The subtitle files that go with the file Play opens, and where to read each (#101).

        A torrent's come from its file list. A download's are its neighbours
        on disk, beside it or in a subtitles folder there, once it's finished.
        """
        task = await self._require(task_id)
        if task.platform == Platform.TORRENT:
            return await self._torrents.subtitle_files(task_id, file_index)
        if task.status != TaskStatus.COMPLETE:
            return []
        path, _, _ = await self.resolve_file(task_id)
        listing = await asyncio.to_thread(folder_listing, path.parent)
        return [(sidecar, path.parent / sidecar.path) for sidecar in match_sidecars(path.name, listing)]

    async def torrent_play(self, task_id: uuid.UUID, file_index: int | None) -> TorrentPlay | None:
        """The torrent to stream when Play is pressed on a torrent still downloading (#95).

        ``None`` when the task plays from disk instead: it isn't a torrent, or
        it has finished. A paused torrent is resumed first, since a stream
        from it would stall; it keeps downloading after the player closes.
        """
        task = await self._require(task_id)
        if task.platform != Platform.TORRENT or task.status in (TaskStatus.COMPLETE, TaskStatus.SEEDING):
            return None
        if task.status not in (TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.PAUSED):
            raise Error.conflict(message=f"Task is {task.status.value}; there's nothing to play")
        index = await self._torrents.media_file_index(task_id, file_index)
        if task.status == TaskStatus.PAUSED:
            await self._torrents.resume(task_id)
        return TorrentPlay(info_hash=task.info_hash or "", file_index=index)

    async def pause(self, task_id: uuid.UUID) -> TaskSchema:
        """Signal a running transfer to stop between chunks, keeping the ``.part``.

        The status is written here rather than by the worker so the caller's
        next read reflects the pause immediately, even if the worker is
        mid-chunk.
        """
        task = await self._require(task_id)
        if task.platform == Platform.TORRENT:
            return await self._torrents.pause(task_id)
        if task.kind == Kind.PLAYLIST:
            for running in await self._repo.pause_entries(task_id):
                self._control.request_stop(running)
            return await self._group_changed(task)
        if task.status not in (TaskStatus.PENDING, TaskStatus.DOWNLOADING):
            raise Error.conflict(message=f"Cannot pause a task that is {task.status.value}")

        self._control.request_stop(task_id)
        task.status = TaskStatus.PAUSED
        task.speed_bps = 0
        task.eta_seconds = None
        await task.save(update_fields=["status", "speed_bps", "eta_seconds"])
        schema = self._published(task)
        await self._refresh_group(task)
        return schema

    async def resume(self, task_id: uuid.UUID) -> TaskSchema:
        """Put a paused or failed task back in the queue, from where its bytes stopped.

        ``attempts`` resets because this is a fresh decision by a person, not a
        continuation of the automatic retry budget that gave up.
        """
        task = await self._require(task_id)
        if task.platform == Platform.TORRENT:
            return await self._torrents.resume(task_id)
        if task.kind == Kind.PLAYLIST:
            await self._repo.resume_entries(task_id)
            self._control.wake()
            return await self._group_changed(task)
        if task.status not in (TaskStatus.PAUSED, TaskStatus.FAILED):
            raise Error.conflict(message=f"Cannot resume a task that is {task.status.value}")

        self._control.clear_stop(task_id)
        task.status = TaskStatus.PENDING
        task.error = None
        task.error_code = None
        task.attempts = 0
        task.next_attempt_at = None
        await task.save(update_fields=["status", "error", "error_code", "attempts", "next_attempt_at"])
        self._control.wake()
        schema = self._published(task)
        await self._refresh_group(task)
        return schema

    async def cancel(self, task_id: uuid.UUID, *, delete_files: bool = True) -> None:
        """Stop the task, soft-delete the row, and take the files or leave them.

        Keeping the files is only offered for a task that finished. A ``.part``
        outlives its row as so many bytes nothing can describe: the watermarks
        that say which ranges are sound live in ``segment``, and cancelling
        clears those.
        """
        task = await self._require(task_id)
        if task.kind == Kind.PLAYLIST:
            return await self._cancel_group(task, delete_files=delete_files)
        if not delete_files and task.status not in (TaskStatus.COMPLETE, TaskStatus.SEEDING):
            raise Error.conflict(
                message=f"Cannot keep the files of a task that is {task.status.value}"
            )
        if task.platform == Platform.TORRENT:
            return await self._torrents.cancel(task_id, delete_files=delete_files)
        self._control.request_stop(task_id)
        if delete_files:
            remove_task_files(self._root, task_id)
            if getattr(task, "parent_id", None) is not None and task.file_path:
                remove_group_video_files(self._root / task.file_path)
        await self._segment_repo.clear(task_id)
        task.status = TaskStatus.CANCELED
        task.deleted_at = now()
        task.speed_bps = 0
        task.eta_seconds = None
        await task.save(update_fields=["status", "deleted_at", "speed_bps", "eta_seconds"])
        self._published(task)
        await self._refresh_group(task)

    def _published(self, task: Any) -> TaskSchema:
        """Serialise the task, announce it, and hand it back to the caller.

        Publishing here rather than only in the worker is what makes a change
        made through the API reach every open browser immediately, instead of
        waiting for the next worker tick.
        """
        schema = TaskSchema.model_validate(task)
        self._hub.publish("task", schema.to_json())
        return schema

    async def _require(self, task_id: uuid.UUID) -> Any:
        task = await self._repo.get_active_by_id(task_id)
        if task is None:
            raise Error.not_found(message="Task not found")
        return task
