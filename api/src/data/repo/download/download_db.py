from __future__ import annotations

import math
import uuid
from collections.abc import Mapping, Sequence
from pathlib import PurePosixPath
from typing import Any

from tortoise import Tortoise
from tortoise.backends.base.client import BaseDBAsyncClient
from tortoise.expressions import Q, Subquery
from tortoise.transactions import in_transaction

from src.core.base import BaseRepo
from src.core.common import now
from src.core.success import Meta
from src.data.db.model import Download, File, Media, Mirror, Source, Torrent, TorrentFile
from src.data.repo.catalog import address_hash, provider_row, source_row, url_row
from src.data.repo.download.interface.download import NOT_CONTAINER, RELATED, DownloadRepo
from src.data.repo.download.interface.file import FileRow
from src.data.repo.download.mime import mime_of
from src.data.schema.download import DownloadSummarySchema
from src.data.type import ACTIVE_STATUSES, DOWNLOAD_GROUPS, DownloadStatus, SourceKind
from src.lib.identity import HTTP_PROVIDER, TORRENT_PROVIDER


def _torrent() -> Q:
    """A torrent: a download with a torrent source. The monitor runs these, never the workers.

    A subquery rather than a join, so a claim's row lock and an update both stay
    on ``download``. Built per call: a queryset needs Tortoise initialised.
    """
    return Q(id__in=Subquery(Mirror.filter(source__kind=SourceKind.TORRENT).values("download_id")))


def _not_torrent() -> Q:
    return ~_torrent()


def _name(path: str) -> str:
    """A file's name: the last part of its path."""
    return PurePosixPath(path).name


#: Which of two downloads holding one video speaks for it: the one furthest along.
_HELD_RANK = {
    DownloadStatus.COMPLETED: 3,
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
    "total_size": "COALESCE(item.total_size, 0)",
    "progress": "item.progress",
    "speed_bps": "COALESCE(live.speed, 0)",
}


class DownloadDatabaseRepo(BaseRepo[Download], DownloadRepo):
    def __init__(self) -> None:
        super().__init__(Download)

    @staticmethod
    async def _loaded(download_id: uuid.UUID) -> Download:
        return await Download.get(id=download_id).prefetch_related(*RELATED)

    @staticmethod
    async def _added(source: Source, download: dict[str, Any], conn: BaseDBAsyncClient) -> Download:
        """The ``Download`` and its one ``Mirror`` onto ``source``, the primary one."""
        row = await Download.create(using_db=conn, **download)
        await Mirror.create(using_db=conn, download=row, source=source)
        return row

    async def create_site(
        self,
        *,
        url: str,
        provider: str,
        download: dict[str, Any],
        media: dict[str, Any],
        filename: str,
        mime_type: str | None,
    ) -> Download:
        async with in_transaction() as conn:
            source = await source_row(
                await provider_row(provider, conn), await url_row(url, conn), SourceKind.CONTENT, conn
            )
            row = await self._added(source, download, conn)
            await Media.create(using_db=conn, download=row, **media)
            await File.create(
                using_db=conn, download=row, index=0, filename=_name(filename), path=filename, mime_type=mime_type
            )
        return await self._loaded(row.id)

    async def create_direct(self, *, url: str, download: dict[str, Any], filename: str) -> Download:
        async with in_transaction() as conn:
            source = await source_row(
                await provider_row(HTTP_PROVIDER, conn), await url_row(url, conn), SourceKind.DIRECT, conn
            )
            row = await self._added(source, download, conn)
            await File.create(
                using_db=conn,
                download=row,
                index=0,
                filename=_name(filename),
                path=filename,
                mime_type=mime_of(filename),
            )
        return await self._loaded(row.id)

    async def create_torrent(
        self,
        *,
        url: str,
        info_hash: str,
        name: str,
        total_size: int | None,
        download: dict[str, Any],
        files: Sequence[FileRow],
    ) -> Download:
        async with in_transaction() as conn:
            known = await Torrent.filter(info_hash=info_hash).using_db(conn).prefetch_related("source").first()
            if known is not None:
                source = known.source
            else:
                source = await source_row(
                    await provider_row(TORRENT_PROVIDER, conn), await url_row(url, conn), SourceKind.TORRENT, conn
                )
                torrent = await Torrent.create(
                    using_db=conn, source=source, name=name, info_hash=info_hash, total_bytes=total_size
                )
                await TorrentFile.bulk_create(
                    [TorrentFile(torrent=torrent, path=path, size=size) for _, path, size, _ in files], using_db=conn
                )
            row = await self._added(source, {**download, "total_size": total_size}, conn)
            if files:
                await File.bulk_create(
                    [
                        File(
                            download=row,
                            index=index,
                            filename=_name(path),
                            path=path,
                            size=size,
                            selected=selected,
                            mime_type=mime_of(path),
                        )
                        for index, path, size, selected in files
                    ],
                    using_db=conn,
                )
        return await self._loaded(row.id)

    async def claim_next(self) -> Download | None:
        """Postgres arbitrates the queue.

        ``FOR UPDATE SKIP LOCKED`` inside a transaction lets several worker
        coroutines pull from one table without ever handing the same row to two
        of them. The status flip commits with the lock.
        """
        async with in_transaction() as conn:
            runnable = Download.filter(
                _not_torrent(), NOT_CONTAINER, status=DownloadStatus.PENDING, deleted_at__isnull=True
            ).filter(Q(next_attempt_at__isnull=True) | Q(next_attempt_at__lte=now()))
            # Standalone first, so a pasted link never waits behind a channel
            # archive; then creation order.
            row = None
            for standalone in (True, False):
                row = await (
                    runnable.filter(parent_id__isnull=standalone)
                    .order_by("created_at")
                    .limit(1)
                    # The download row only: the container check joins media, and
                    # Postgres will not lock the nullable side of an outer join.
                    .select_for_update(skip_locked=True, of=("download",))
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

        ``downloaded_size`` stays: the ``.part`` still holds those bytes. Torrents
        are the monitor's to reconcile, never requeued here.
        """
        return await Download.filter(_not_torrent(), status__in=list(ACTIVE_STATUSES), deleted_at__isnull=True).update(
            status=DownloadStatus.PENDING
        )

    async def flush_progress(self, download_id: uuid.UUID, *, downloaded_size: int, total_size: int | None) -> None:
        await Download.filter(id=download_id).update(downloaded_size=downloaded_size, total_size=total_size)

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
            "FROM transfer.list_item AS item "
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
            "SELECT status, COUNT(*) AS n FROM transfer.list_item GROUP BY status"
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
            Download.filter(NOT_CONTAINER, deleted_at__isnull=True, parent_id__isnull=True, status__in=list(statuses))
            .order_by("created_at")
            .prefetch_related(*RELATED)
        )

    async def get_active_by_id(self, download_id: uuid.UUID) -> Download | None:
        return await (
            Download.filter(NOT_CONTAINER, id=download_id, deleted_at__isnull=True).prefetch_related(*RELATED).first()
        )

    async def statuses_by_url(self, urls: Sequence[str]) -> dict[str, DownloadStatus]:
        if not urls:
            return {}
        by_hash: dict[str, list[str]] = {}
        for url in urls:
            by_hash.setdefault(address_hash(url), []).append(url)
        rows = (
            await Download.filter(
                NOT_CONTAINER,
                mirrors__source__url__normalized_hash__in=list(by_hash),
                deleted_at__isnull=True,
            )
            .exclude(status=DownloadStatus.CANCELLED)
            .values_list("mirrors__source__url__normalized_hash", "status")
        )
        best: dict[str, DownloadStatus] = {}
        for hashed, raw in rows:
            status = DownloadStatus(raw)
            current = best.get(hashed)
            if current is None or _HELD_RANK.get(status, 0) > _HELD_RANK.get(current, 0):
                best[hashed] = status
        return {url: status for hashed, status in best.items() for url in by_hash[hashed]}

    async def torrents_to_watch(self) -> list[Download]:
        return await (
            Download.filter(_torrent(), deleted_at__isnull=True).order_by("created_at").prefetch_related(*RELATED)
        )

    async def set_torrent_total(self, info_hash: str, total: int) -> None:
        await Torrent.filter(info_hash=info_hash).update(total_bytes=total)

    async def by_info_hash(self, info_hash: str) -> Download | None:
        return await (
            Download.filter(mirrors__source__torrents__info_hash=info_hash, deleted_at__isnull=True)
            .prefetch_related(*RELATED)
            .first()
        )
