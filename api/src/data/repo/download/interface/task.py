from __future__ import annotations

import uuid
from abc import abstractmethod
from collections.abc import Sequence
from typing import Any

from src.core.base import CrudRepo
from src.core.success import Meta
from src.data.db.model import Task
from src.data.schema.download import TaskSummarySchema
from src.data.type import TaskStatus


class TaskRepo(CrudRepo[Task]):
    @abstractmethod
    async def claim_next(self) -> Task | None:
        """Take the oldest runnable pending task and mark it ``downloading``.

        Runnable means ``next_attempt_at`` is unset or already past. Returns
        ``None`` when the queue is empty.
        """
        ...

    @abstractmethod
    async def statuses_by_video(self, extractor: str, video_ids: Sequence[str]) -> dict[str, TaskStatus]:
        """Each of ``video_ids`` that a task not removed holds, with that task's status.

        For the picker's "already have it". Canceled tasks hold nothing. A
        video held twice reports the task furthest along: complete, then
        queued, then failed.
        """
        ...

    @abstractmethod
    async def create_group(self, group: dict[str, Any], entries: Sequence[dict[str, Any]]) -> Task:
        """A playlist's row and its videos, in one transaction (v0.5).

        Every video gets the group as ``parent`` and the group's ``created_at``,
        so the queue takes them in ``position`` order.
        """
        ...

    @abstractmethod
    async def entries_page(self, group_id: uuid.UUID, page: int, page_size: int) -> tuple[list[Task], Meta]:
        """One page of a group's videos not removed, by ``position``."""
        ...

    @abstractmethod
    async def entry_statuses(self, group_id: uuid.UUID) -> list[tuple[TaskStatus, int, int | None, int]]:
        """``(status, downloaded_bytes, total_bytes, speed_bps)`` of each video not removed."""
        ...

    @abstractmethod
    async def find_group(self, extractor: str, playlist_id: str) -> Task | None:
        """The group not removed that was added from this playlist, if any."""
        ...

    @abstractmethod
    async def recover_orphans(self) -> int:
        """Requeue every task left mid-flight by a dead process. Returns the count."""
        ...

    @abstractmethod
    async def flush_progress(
        self,
        task_id: uuid.UUID,
        *,
        downloaded_bytes: int,
        total_bytes: int | None,
        progress: int,
        speed_bps: int,
        eta_seconds: int | None,
    ) -> None:
        """Write one progress sample and stamp ``heartbeat_at``."""
        ...

    @abstractmethod
    async def list_page(
        self,
        page: int,
        page_size: int,
        statuses: list[TaskStatus] | None = None,
        sort: str = "-created_at",
    ) -> tuple[list[Task], Meta]:
        ...

    @abstractmethod
    async def by_statuses(self, statuses: list[TaskStatus]) -> list[Task]:
        ...

    @abstractmethod
    async def summary(self) -> TaskSummarySchema:
        ...

    @abstractmethod
    async def get_active_by_id(self, task_id: uuid.UUID) -> Task | None:
        ...

    @abstractmethod
    async def torrents_to_watch(self) -> list[Task]:
        """Every torrent row the monitor should mirror.

        Soft-deleted rows are excluded because cancelling removed the torrent
        from the engine too. Terminal statuses are deliberately *not* excluded:
        a failed torrent whose engine recovers should come back to life, and a
        complete one is guarded inside ``status_for``.
        """
        ...
