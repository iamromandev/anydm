from __future__ import annotations

import uuid
from typing import Any

from tortoise.expressions import Q
from tortoise.functions import Coalesce
from tortoise.transactions import in_transaction

from src.core.base import BaseRepo
from src.core.common import now
from src.core.success import Meta
from src.data.db.model import Task
from src.data.repo.download.interface import TaskRepo
from src.data.schema.download import TaskSummarySchema
from src.data.type import ACTIVE_STATUSES, TASK_GROUPS, Platform, TaskStatus


class TaskDatabaseRepo(BaseRepo[Task], TaskRepo):
    def __init__(self) -> None:
        super().__init__(Task)

    async def claim_next(self) -> Task | None:
        """Postgres arbitrates the queue.

        ``FOR UPDATE SKIP LOCKED`` inside a transaction is what lets several
        worker coroutines — and, later, several processes — pull from one table
        without ever handing the same row to two of them. The status flip
        commits with the lock, so the row is unclaimable the moment it is taken.
        """
        async with in_transaction() as conn:
            task = await (
                Task.filter(status=TaskStatus.PENDING, deleted_at__isnull=True)
                .exclude(platform=Platform.TORRENT)
                .filter(Q(next_attempt_at__isnull=True) | Q(next_attempt_at__lte=now()))
                .order_by("created_at")
                .limit(1)
                .select_for_update(skip_locked=True)
                .using_db(conn)
                .first()
            )
            if task is None:
                return None

            task.status = TaskStatus.DOWNLOADING
            task.started_at = task.started_at or now()
            task.heartbeat_at = now()
            await task.save(using_db=conn)
            return task

    async def recover_orphans(self) -> int:
        """Every in-flight row at startup belongs to a process that is gone.

        ``downloaded_bytes`` is deliberately left alone: the ``.part`` file on
        disk still holds those bytes, and the next claim resumes from there.

        Torrents are excluded because they were never in this pool. rqbit moved
        their bytes and rqbit's own session survived the restart; the torrent
        monitor reconciles them instead. Requeueing one would hand it to a
        worker that cannot download it.
        """
        return await (
            Task.filter(status__in=list(ACTIVE_STATUSES), deleted_at__isnull=True)
            .exclude(platform=Platform.TORRENT)
            .update(
                status=TaskStatus.PENDING,
                speed_bps=0,
                eta_seconds=None,
            )
        )

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
        await Task.filter(id=task_id).update(
            downloaded_bytes=downloaded_bytes,
            total_bytes=total_bytes,
            progress=progress,
            speed_bps=speed_bps,
            eta_seconds=eta_seconds,
            heartbeat_at=now(),
        )

    async def list_page(
        self,
        page: int,
        page_size: int,
        statuses: list[TaskStatus] | None = None,
        sort: str = "-created_at",
    ) -> tuple[list[Task], Meta]:
        """One page of the list, newest first, optionally narrowed by status.

        The filter is applied in the database rather than after the fact so
        that ``Meta.total`` describes the filtered set. A total that counted
        rows the caller will never be sent is a "load more" button that never
        stops offering.
        """
        filters: dict[str, Any] = {"deleted_at__isnull": True}
        if statuses:
            filters["status__in"] = list(statuses)

        # NULL means "the server never said how big it is". Postgres sorts
        # NULL first on a descending order, which would put the one task
        # nobody knows the size of at the top of "largest first".
        annotations = None
        order = sort
        if sort.lstrip("-") == "total_bytes":
            annotations = {"known_size": Coalesce("total_bytes", 0)}
            order = f"{'-' if sort.startswith('-') else ''}known_size"

        tasks, meta = await self.get_paginated(
            order_by=order,
            annotations=annotations,
            page=page,
            page_size=page_size,
            **filters,
        )
        return tasks, Meta(**meta)

    async def by_statuses(self, statuses: list[TaskStatus]) -> list[Task]:
        """Every live row in one of ``statuses``, oldest first.

        Oldest first because a bulk action reads better applied in the order
        the queue would have reached them, and unpaginated because the point
        of "pause all" is that it means all of them.
        """
        return await Task.filter(
            deleted_at__isnull=True, status__in=list(statuses)
        ).order_by("created_at")

    async def summary(self) -> TaskSummarySchema:
        """How many tasks each sidebar filter would show.

        Four counts rather than one grouped query: Tortoise makes conditional
        aggregation awkward enough that the clever version would need more
        explaining than it saves, and this runs on a page load, not a tick.
        """

        async def count(group: str | None = None) -> int:
            query = Task.filter(deleted_at__isnull=True)
            if group is not None:
                query = query.filter(status__in=list(TASK_GROUPS[group]))
            return await query.count()

        return TaskSummarySchema(
            all=await count(),
            downloading=await count("downloading"),
            seeding=await count("seeding"),
            completed=await count("completed"),
        )

    async def get_active_by_id(self, task_id: uuid.UUID) -> Task | None:
        return await self.get_by_id(task_id, deleted_at__isnull=True)

    async def torrents_to_watch(self) -> list[Task]:
        return await Task.filter(platform=Platform.TORRENT, deleted_at__isnull=True).order_by(
            "created_at"
        )
