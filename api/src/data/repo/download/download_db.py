from __future__ import annotations

import math
import uuid
from collections.abc import Mapping, Sequence
from typing import Any

from tortoise import Tortoise
from tortoise.backends.base.client import BaseDBAsyncClient
from tortoise.expressions import Q
from tortoise.transactions import in_transaction

from src.core.base import BaseRepo
from src.core.common import now
from src.core.success import Meta
from src.data.db.model import Download, DownloadFile, Queue, SiteDetail, TorrentDetail
from src.data.repo.download.interface.download import RELATED, DownloadRepo
from src.data.repo.download.interface.file import FileRow
from src.data.schema.download import DownloadSummarySchema
from src.data.type import ACTIVE_STATUSES, DOWNLOAD_GROUPS, MAIN_QUEUE, DownloadStatus, Platform

#: Which of two downloads holding one video speaks for it: the one furthest along.
_HELD_RANK = {
    DownloadStatus.COMPLETE: 3,
    DownloadStatus.SEEDING: 3,
    DownloadStatus.PENDING: 2,
    DownloadStatus.DOWNLOADING: 2,
    DownloadStatus.MUXING: 2,
    DownloadStatus.PAUSED: 2,
    DownloadStatus.FAILED: 1,
}

#: ``list_item`` expressions each sort reads. Spelled out so a sort value never
#: reaches SQL as text: an unknown one is a ``KeyError`` here.
_ORDER = {
    "created_at": "item.created_at",
    "title": "lower(item.title)",
    # NULL means "the size is unknown"; it sorts as 0, not ahead of everything.
    "total_bytes": "COALESCE(item.total_bytes, 0)",
    "progress": "item.progress",
    "speed_bps": "COALESCE(live.speed, 0)",
}


class DownloadDatabaseRepo(BaseRepo[Download], DownloadRepo):
    def __init__(self) -> None:
        super().__init__(Download)

    @staticmethod
    async def _placed(download: dict[str, Any], conn: BaseDBAsyncClient) -> dict[str, Any]:
        """``download`` in a queue (Main unless named) and at that queue's end."""
        row = dict(download)
        if "queue_id" not in row:
            row["queue_id"] = (await Queue.get(name=MAIN_QUEUE).using_db(conn)).id
        if "queue_position" not in row:
            last = await (
                Download.filter(queue_id=row["queue_id"])
                .using_db(conn)
                .order_by("-queue_position")
                .limit(1)
                .values_list("queue_position", flat=True)
            )
            row["queue_position"] = (last[0] + 1) if last else 0
        return row

    @staticmethod
    async def _loaded(download_id: uuid.UUID) -> Download:
        return await Download.get(id=download_id).prefetch_related(*RELATED)

    async def create_site(
        self, download: dict[str, Any], site: dict[str, Any], filename: str, mime_type: str | None
    ) -> Download:
        async with in_transaction() as conn:
            row = await Download.create(using_db=conn, **await self._placed(download, conn))
            await SiteDetail.create(using_db=conn, download_id=row.id, **site)
            await DownloadFile.create(using_db=conn, download_id=row.id, index=0, path=filename, mime_type=mime_type)
        return await self._loaded(row.id)

    async def create_direct(self, download: dict[str, Any], filename: str) -> Download:
        async with in_transaction() as conn:
            row = await Download.create(using_db=conn, **await self._placed(download, conn))
            await DownloadFile.create(using_db=conn, download_id=row.id, index=0, path=filename)
        return await self._loaded(row.id)

    async def create_torrent(self, download: dict[str, Any], info_hash: str, files: Sequence[FileRow]) -> Download:
        async with in_transaction() as conn:
            row = await Download.create(using_db=conn, **await self._placed(download, conn))
            await TorrentDetail.create(using_db=conn, download_id=row.id, info_hash=info_hash)
            if files:
                await DownloadFile.bulk_create(
                    [
                        DownloadFile(download_id=row.id, index=index, path=path, size_bytes=size, selected=selected)
                        for index, path, size, selected in files
                    ],
                    using_db=conn,
                )
        return await self._loaded(row.id)

    async def claim_next(self, queue_ids: Sequence[uuid.UUID] | None = None) -> Download | None:
        """Postgres arbitrates the queue.

        ``FOR UPDATE SKIP LOCKED`` inside a transaction lets several worker
        coroutines pull from one table without ever handing the same row to two
        of them. The status flip commits with the lock.
        """
        async with in_transaction() as conn:
            runnable = (
                Download.filter(status=DownloadStatus.PENDING, deleted_at__isnull=True)
                .exclude(platform=Platform.TORRENT)
                .filter(Q(next_attempt_at__isnull=True) | Q(next_attempt_at__lte=now()))
                .filter(Q(start_at__isnull=True) | Q(start_at__lte=now()))
            )
            if queue_ids is not None:
                runnable = runnable.filter(queue_id__in=list(queue_ids))
            # Standalone first, so a pasted link never waits behind a channel
            # archive (phase-1 parity; the queue slice replaces this with queue order).
            row = None
            for standalone in (True, False):
                row = await (
                    runnable.filter(collection_id__isnull=standalone)
                    .order_by("queue_position", "created_at")
                    .limit(1)
                    .select_for_update(skip_locked=True)
                    .using_db(conn)
                    .first()
                )
                if row is not None:
                    break
            if row is None:
                return None
            row.status = DownloadStatus.DOWNLOADING
            row.started_at = row.started_at or now()
            await row.save(using_db=conn, update_fields=["status", "started_at"])
        await row.fetch_related(*RELATED)
        return row

    async def recover_orphans(self) -> int:
        """Every in-flight HTTP row at startup belongs to a process that is gone.

        ``downloaded_bytes`` stays: the ``.part`` still holds those bytes. Torrents
        are the monitor's to reconcile, never requeued here.
        """
        return await (
            Download.filter(status__in=list(ACTIVE_STATUSES), deleted_at__isnull=True)
            .exclude(platform=Platform.TORRENT)
            .update(status=DownloadStatus.PENDING)
        )

    async def flush_progress(self, download_id: uuid.UUID, *, downloaded_bytes: int, total_bytes: int | None) -> None:
        await Download.filter(id=download_id).update(downloaded_bytes=downloaded_bytes, total_bytes=total_bytes)

    async def list_items(
        self,
        page: int,
        page_size: int,
        statuses: Sequence[DownloadStatus] | None,
        sort: str,
        speeds: Mapping[uuid.UUID, int],
    ) -> tuple[list[tuple[str, uuid.UUID]], Meta]:
        order = _ORDER[sort.lstrip("-")]
        direction = "DESC" if sort.startswith("-") else "ASC"
        params: list[Any] = [list(speeds), list(speeds.values())]
        where = ""
        if statuses:
            params.append([status.value for status in statuses])
            where = f"WHERE item.status = ANY(${len(params)}::text[])"
        source = (
            "FROM list_item AS item "
            "LEFT JOIN unnest($1::uuid[], $2::bigint[]) AS live(id, speed) ON live.id = item.id "
            f"{where}"
        )
        conn = Tortoise.get_connection("default")
        total = int((await conn.execute_query_dict(f"SELECT COUNT(*) AS n {source}", params))[0]["n"])
        rows = await conn.execute_query_dict(
            f"SELECT item.type, item.id {source} "
            f"ORDER BY {order} {direction}, item.created_at DESC, item.id "
            f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}",
            [*params, page_size, (page - 1) * page_size],
        )
        meta = Meta(page=page, page_size=page_size, total=total, total_pages=max(1, math.ceil(total / page_size)))
        return [(str(row["type"]), row["id"]) for row in rows], meta

    async def summary(self) -> DownloadSummarySchema:
        """Counted from ``list_item``, so a collection counts once, by its computed status.

        Counted in the database rather than from the rows a browser holds, so the
        numbers stay right however little of the list has been loaded.
        """
        rows = await Tortoise.get_connection("default").execute_query_dict(
            "SELECT status, COUNT(*) AS n FROM list_item GROUP BY status"
        )
        counts = {str(row["status"]): int(row["n"]) for row in rows}

        def group(name: str) -> int:
            return sum(counts.get(status.value, 0) for status in DOWNLOAD_GROUPS[name])

        return DownloadSummarySchema(
            all=sum(counts.values()),
            downloading=group("downloading"),
            seeding=group("seeding"),
            completed=group("completed"),
        )

    async def by_ids(self, ids: Sequence[uuid.UUID]) -> list[Download]:
        if not ids:
            return []
        found = {row.id: row for row in await Download.filter(id__in=list(ids)).prefetch_related(*RELATED)}
        return [found[download_id] for download_id in ids if download_id in found]

    async def by_statuses(self, statuses: Sequence[DownloadStatus]) -> list[Download]:
        """Oldest first: a bulk action reads better applied in the order the queue would reach them."""
        return await (
            Download.filter(deleted_at__isnull=True, collection_id__isnull=True, status__in=list(statuses))
            .order_by("created_at")
            .prefetch_related(*RELATED)
        )

    async def get_active_by_id(self, download_id: uuid.UUID) -> Download | None:
        return await Download.filter(id=download_id, deleted_at__isnull=True).prefetch_related(*RELATED).first()

    async def statuses_by_video(self, extractor: str, video_ids: Sequence[str]) -> dict[str, DownloadStatus]:
        if not video_ids:
            return {}
        rows = (
            await SiteDetail.filter(
                extractor=extractor, video_id__in=list(video_ids), download__deleted_at__isnull=True
            )
            .exclude(download__status=DownloadStatus.CANCELED)
            .values_list("video_id", "download__status")
        )
        found: dict[str, DownloadStatus] = {}
        for video_id, raw in rows:
            status = DownloadStatus(raw)
            current = found.get(video_id)
            if current is None or _HELD_RANK.get(status, 0) > _HELD_RANK.get(current, 0):
                found[video_id] = status
        return found

    async def torrents_to_watch(self) -> list[Download]:
        return await (
            Download.filter(platform=Platform.TORRENT, deleted_at__isnull=True)
            .order_by("created_at")
            .prefetch_related(*RELATED)
        )

    async def by_info_hash(self, info_hash: str) -> Download | None:
        return await (
            Download.filter(torrent_detail__info_hash=info_hash, deleted_at__isnull=True)
            .prefetch_related(*RELATED)
            .first()
        )
