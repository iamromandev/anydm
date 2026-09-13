from __future__ import annotations

import uuid

from src.core.base import BaseService
from src.core.error import Error
from src.core.success import Meta
from src.data.repo.download.interface import TaskRepo
from src.data.schema.download import TaskSchema
from src.data.type import Platform, Preset, TaskStatus
from src.lib.youtube import (
    YouTubeClient,
    extract_video_id,
    not_a_youtube_url,
    safe_filename,
    select_plan,
)


class DownloadService(BaseService):
    def __init__(self, repo: TaskRepo, client: YouTubeClient) -> None:
        super().__init__()
        self._repo = repo
        self._client = client

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
            status=TaskStatus.PENDING,
            progress=0,
        )
        return TaskSchema.model_validate(task)

    async def list_tasks(self, page: int, page_size: int) -> tuple[list[TaskSchema], Meta]:
        tasks, meta = await self._repo.list_page(page=page, page_size=page_size)
        return [TaskSchema.model_validate(task) for task in tasks], meta

    async def get_task(self, task_id: uuid.UUID) -> TaskSchema:
        task = await self._repo.get_active_by_id(task_id)
        if task is None:
            raise Error.not_found(message="Task not found")
        return TaskSchema.model_validate(task)
