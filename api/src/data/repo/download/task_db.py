from __future__ import annotations

import uuid
from collections.abc import Sequence
from typing import Any, cast

from tortoise.expressions import Q
from tortoise.functions import Coalesce
from tortoise.transactions import in_transaction

from src.core.base import BaseRepo
from src.core.common import now
from src.core.success import Meta
from src.data.db.model import Task
from src.data.repo.download.interface import TaskRepo
from src.data.schema.download import TaskSummarySchema
from src.data.type import ACTIVE_STATUSES, TASK_GROUPS, Kind, Platform, TaskStatus

#: Which of two tasks holding one video speaks for it: the one furthest along.
_HELD_RANK = {
    TaskStatus.COMPLETE: 3,
    TaskStatus.SEEDING: 3,
    TaskStatus.PENDING: 2,
    TaskStatus.DOWNLOADING: 2,
    TaskStatus.MUXING: 2,
    TaskStatus.PAUSED: 2,
    TaskStatus.FAILED: 1,
}


async def _ids(query: Any, column: str) -> list[uuid.UUID]:
    """One uuid column of ``query``'s rows. Tortoise types a flat ``values_list`` as tuples."""
    return cast(list[uuid.UUID], list(await query.values_list(column, flat=True)))


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
            runnable = (
                Task.filter(status=TaskStatus.PENDING, deleted_at__isnull=True)
                .exclude(platform=Platform.TORRENT)
                .exclude(kind=Kind.PLAYLIST)
                .filter(Q(next_attempt_at__isnull=True) | Q(next_attempt_at__lte=now()))
            )
            # Standalone tasks first, so a pasted link never waits behind a
            # channel archive; then a group's videos in playlist order.
            task = await (
                runnable.filter(parent_id__isnull=True)
                .order_by("created_at")
                .limit(1)
                .select_for_update(skip_locked=True)
                .using_db(conn)
                .first()
            )
            if task is None:
                task = await (
                    runnable.filter(parent_id__isnull=False)
                    .order_by("created_at", "position")
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
        # Top-level rows only: a group's videos are listed through its card.
        filters: dict[str, Any] = {"deleted_at__isnull": True, "parent_id__isnull": True}
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
            query = Task.filter(deleted_at__isnull=True, parent_id__isnull=True)
            if group is not None:
                query = query.filter(status__in=list(TASK_GROUPS[group]))
            return await query.count()

        return TaskSummarySchema(
            all=await count(),
            downloading=await count("downloading"),
            seeding=await count("seeding"),
            completed=await count("completed"),
        )

    async def statuses_by_video(self, extractor: str, video_ids: Sequence[str]) -> dict[str, TaskStatus]:
        if not video_ids:
            return {}
        rows = (
            await Task.filter(deleted_at__isnull=True, extractor=extractor, video_id__in=list(video_ids))
            .exclude(status=TaskStatus.CANCELED)
            .values_list("video_id", "status")
        )
        found: dict[str, TaskStatus] = {}
        for video_id, raw_status in rows:
            status = TaskStatus(raw_status)
            current = found.get(video_id)
            if current is None or _HELD_RANK.get(status, 0) > _HELD_RANK.get(current, 0):
                found[video_id] = status
        return found

    async def create_group(self, group: dict[str, Any], entries: Sequence[dict[str, Any]]) -> Task:
        async with in_transaction() as conn:
            row = await Task.create(using_db=conn, **group)
            if entries:
                await Task.bulk_create(
                    [Task(**entry, parent_id=row.id, created_at=row.created_at) for entry in entries],
                    batch_size=500,
                    using_db=conn,
                )
        return row

    async def held_entries(self, group_id: uuid.UUID) -> dict[str, tuple[uuid.UUID, TaskStatus, int | None]]:
        """A group's videos by their site id: which task, how it stands, where it sits."""
        rows = cast(
            list[tuple[str, uuid.UUID, str, int | None]],
            await Task.filter(parent_id=group_id, deleted_at__isnull=True).values_list(
                "video_id", "id", "status", "position"
            ),
        )
        return {video_id: (task_id, TaskStatus(status), position) for video_id, task_id, status, position in rows}

    async def add_entries(self, group: Task, entries: Sequence[dict[str, Any]]) -> None:
        """More videos under a group, queued with its first ones (by its ``created_at``)."""
        await Task.bulk_create(
            [Task(**entry, parent_id=group.id, created_at=group.created_at) for entry in entries],
            batch_size=500,
        )

    async def requeue_videos(self, ids: Sequence[uuid.UUID]) -> int:
        """These videos back in the queue, as a person's fresh decision."""
        if not ids:
            return 0
        return await self._requeue(Task.filter(id__in=list(ids), deleted_at__isnull=True))

    async def entries_page(self, group_id: uuid.UUID, page: int, page_size: int) -> tuple[list[Task], Meta]:
        tasks, meta = await self.get_paginated(
            order_by="position",
            page=page,
            page_size=page_size,
            deleted_at__isnull=True,
            parent_id=group_id,
        )
        return tasks, Meta(**meta)

    async def entry_statuses(self, group_id: uuid.UUID) -> list[tuple[TaskStatus, int, int | None, int]]:
        rows = await Task.filter(parent_id=group_id, deleted_at__isnull=True).values_list(
            "status", "downloaded_bytes", "total_bytes", "speed_bps"
        )
        return [(TaskStatus(status), done, total, speed) for status, done, total, speed in rows]

    async def pause_entries(self, group_id: uuid.UUID) -> list[uuid.UUID]:
        videos = Task.filter(parent_id=group_id, deleted_at__isnull=True)
        return await self._pause(videos)

    async def resume_entries(self, group_id: uuid.UUID) -> int:
        videos = Task.filter(parent_id=group_id, deleted_at__isnull=True)
        return await self._requeue(videos)

    async def remove_entries(self, group_id: uuid.UUID) -> list[uuid.UUID]:
        videos = Task.filter(parent_id=group_id, deleted_at__isnull=True)
        ids = await _ids(videos, "id")
        await videos.update(status=TaskStatus.CANCELED, deleted_at=now(), speed_bps=0, eta_seconds=None)
        return ids

    async def pause_all_entries(self) -> tuple[list[uuid.UUID], set[uuid.UUID]]:
        videos = Task.filter(parent_id__isnull=False, deleted_at__isnull=True)
        groups = set(
            await _ids(videos.filter(status__in=[TaskStatus.PENDING, TaskStatus.DOWNLOADING]), "parent_id")
        )
        return await self._pause(videos), groups

    async def resume_all_entries(self) -> set[uuid.UUID]:
        videos = Task.filter(parent_id__isnull=False, deleted_at__isnull=True)
        groups = set(await _ids(videos.filter(status__in=[TaskStatus.PAUSED, TaskStatus.FAILED]), "parent_id"))
        await self._requeue(videos)
        return groups

    @staticmethod
    async def _pause(videos: Any) -> list[uuid.UUID]:
        """Pause what's queued or downloading among ``videos``; the ids that were downloading."""
        running = await _ids(videos.filter(status=TaskStatus.DOWNLOADING), "id")
        await videos.filter(status__in=[TaskStatus.PENDING, TaskStatus.DOWNLOADING]).update(
            status=TaskStatus.PAUSED, speed_bps=0, eta_seconds=None
        )
        return running

    @staticmethod
    async def _requeue(videos: Any) -> int:
        """Put the paused and failed among ``videos`` back in the queue, as a person's fresh decision."""
        return await videos.filter(status__in=[TaskStatus.PAUSED, TaskStatus.FAILED]).update(
            status=TaskStatus.PENDING, attempts=0, error=None, error_code=None, next_attempt_at=None
        )

    async def find_group(self, extractor: str, playlist_id: str) -> Task | None:
        return await Task.filter(
            kind=Kind.PLAYLIST, extractor=extractor, video_id=playlist_id, deleted_at__isnull=True
        ).first()

    async def get_active_by_id(self, task_id: uuid.UUID) -> Task | None:
        return await self.get_by_id(task_id, deleted_at__isnull=True)

    async def torrents_to_watch(self) -> list[Task]:
        return await Task.filter(platform=Platform.TORRENT, deleted_at__isnull=True).order_by(
            "created_at"
        )
