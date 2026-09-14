from __future__ import annotations

import uuid
from pathlib import Path
from typing import Any

from src.core.base import BaseService
from src.core.common import now
from src.core.error import Error
from src.core.success import Meta
from src.data.repo.download.interface import TaskRepo, TaskSegmentRepo
from src.data.schema.download import TaskSchema
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.event import EventHub
from src.lib.youtube import (
    YouTubeClient,
    extract_video_id,
    not_a_youtube_url,
    safe_filename,
    select_plan,
)
from src.service.download.control import DownloadControl
from src.service.download.direct import ensure_fetchable, filename_from_url
from src.service.download.download_worker import remove_task_files


class DownloadService(BaseService):
    def __init__(
        self,
        repo: TaskRepo,
        segment_repo: TaskSegmentRepo,
        client: YouTubeClient,
        control: DownloadControl,
        hub: EventHub,
        downloads_root: Path,
    ) -> None:
        super().__init__()
        self._repo = repo
        self._segment_repo = segment_repo
        self._client = client
        self._control = control
        self._hub = hub
        self._root = downloads_root

    async def enqueue_youtube(self, url: str, preset: Preset) -> TaskSchema:
        """Resolve the plan now, move the bytes later.

        Everything that can fail on the caller's behalf — a bad URL, a private
        video, a preset with no matching stream — fails here, as a 4xx they see
        immediately. What reaches the queue is a decision, not a guess.
        """
        video_id = extract_video_id(url)
        if video_id is None:
            raise not_a_youtube_url()

        info = await self._client.fetch_info(video_id)
        plan = select_plan(info.streams, preset)
        suffix = "" if preset == Preset.MP3 else plan.quality

        task = await self._repo.create(
            source_url=url,
            platform=Platform.YOUTUBE,
            video_id=video_id,
            preset=preset,
            kind=plan.kind,
            title=info.title,
            filename=safe_filename(info.title, suffix, plan.extension),
            mime_type=plan.mime_type,
            video_itag=plan.video_itag,
            audio_itag=plan.audio_itag,
            total_bytes=plan.expected_bytes,
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
            video_itag=None,
            audio_itag=None,
            total_bytes=None,
            status=TaskStatus.PENDING,
            progress=0,
        )
        self._control.wake()
        return self._published(task)

    async def list_tasks(self, page: int, page_size: int) -> tuple[list[TaskSchema], Meta]:
        tasks, meta = await self._repo.list_page(page=page, page_size=page_size)
        return [TaskSchema.model_validate(task) for task in tasks], meta

    async def get_task(self, task_id: uuid.UUID) -> TaskSchema:
        return TaskSchema.model_validate(await self._require(task_id))

    async def resolve_file(self, task_id: uuid.UUID) -> tuple[Path, str, str]:
        """The finished file for ``task_id``.

        409 rather than 404 while a task is still running: the resource will
        exist, just not yet — which is what the Bun API said for a verifying
        torrent, and what a polling client needs to tell "wait" from "never".
        """
        task = await self._require(task_id)

        if task.status != TaskStatus.COMPLETE or not task.file_path:
            raise Error.conflict(message=f"Task is {task.status.value}, not complete")

        path = self._root / task.file_path
        if not path.is_file():
            raise Error.not_found(message="File is no longer on disk")

        return path, task.filename, task.mime_type or "application/octet-stream"

    async def pause(self, task_id: uuid.UUID) -> TaskSchema:
        """Signal a running transfer to stop between chunks, keeping the ``.part``.

        The status is written here rather than by the worker so the caller's
        next read reflects the pause immediately, even if the worker is
        mid-chunk.
        """
        task = await self._require(task_id)
        if task.status not in (TaskStatus.PENDING, TaskStatus.DOWNLOADING):
            raise Error.conflict(message=f"Cannot pause a task that is {task.status.value}")

        self._control.request_stop(task_id)
        task.status = TaskStatus.PAUSED
        task.speed_bps = 0
        task.eta_seconds = None
        await task.save(update_fields=["status", "speed_bps", "eta_seconds"])
        return self._published(task)

    async def resume(self, task_id: uuid.UUID) -> TaskSchema:
        """Put a paused or failed task back in the queue, from where its bytes stopped.

        ``attempts`` resets because this is a fresh decision by a person, not a
        continuation of the automatic retry budget that gave up.
        """
        task = await self._require(task_id)
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
        return self._published(task)

    async def cancel(self, task_id: uuid.UUID) -> None:
        """Stop the task, delete its files, and soft-delete the row."""
        task = await self._require(task_id)
        self._control.request_stop(task_id)
        remove_task_files(self._root, task_id)
        await self._segment_repo.clear(task_id)
        task.status = TaskStatus.CANCELED
        task.deleted_at = now()
        task.speed_bps = 0
        task.eta_seconds = None
        await task.save(update_fields=["status", "deleted_at", "speed_bps", "eta_seconds"])
        self._published(task)

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
