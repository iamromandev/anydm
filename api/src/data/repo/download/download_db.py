from __future__ import annotations

import math
import uuid
from collections import Counter
from collections.abc import Callable, Collection, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from pathlib import PurePosixPath
from types import SimpleNamespace
from typing import Any

from tortoise.backends.base.client import BaseDBAsyncClient
from tortoise.expressions import Q, Subquery
from tortoise.transactions import in_transaction

from src.core.base import BaseRepo
from src.core.common import now
from src.core.success import Meta
from src.data.db.model import Download, File, Media, Mirror, Source, Torrent, TorrentFile
from src.data.repo.catalog import address_hash, provider_row, source_row, url_row
from src.data.repo.download.described import describe, primary_mirror
from src.data.repo.download.interface.download import NOT_CONTAINER, RELATED, DownloadRepo
from src.data.repo.download.interface.file import FileRow
from src.data.repo.download.mime import mime_of
from src.data.schema.transfer import DownloadSummarySchema
from src.data.type import (
    ACTIVE_STATUSES,
    CONTAINER_KINDS,
    DONE_STATUSES,
    DOWNLOAD_GROUPS,
    DownloadStatus,
    SourceKind,
    collection_status,
)
from src.lib.identity import HTTP_PROVIDER, TORRENT_PROVIDER


def _torrent() -> Q:
    """A torrent: a download with a torrent source. The monitor runs these, never the workers.

    A subquery rather than a join, so a claim's row lock and an update both stay
    on ``download``. Built per call: a queryset needs Tortoise initialised.
    """
    return Q(id__in=Subquery(Mirror.filter(source__kind=SourceKind.TORRENT).values("download_id")))


def _not_torrent() -> Q:
    return ~_torrent()


def _torrent_named(row: Download) -> bool:
    """Whether a torrent names it, through its primary mirror: then no file is needed for its title."""
    mirror = primary_mirror(row)
    return mirror is not None and any(torrent.name for torrent in mirror.source.torrents)


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


@dataclass(frozen=True, slots=True)
class _Item:
    """One row of the list: a standalone download or a collection, its status and bytes computed from its videos."""

    type: str
    id: uuid.UUID
    title: str
    status: DownloadStatus
    created_at: datetime
    total_size: int | None
    downloaded_size: int
    progress: int


#: What each sort reads off an item; ``speeds`` are the live ones. Spelled out so
#: an unknown sort value is a ``KeyError`` here.
_ORDER: dict[str, Callable[[_Item, Mapping[uuid.UUID, int]], Any]] = {
    "created_at": lambda item, _: item.created_at,
    "title": lambda item, _: item.title.lower(),
    # None means "the size is unknown"; it sorts as 0, not ahead of everything.
    "total_size": lambda item, _: item.total_size or 0,
    "progress": lambda item, _: item.progress,
    "speed_bps": lambda item, speeds: speeds.get(item.id, 0),
}


def _ordered(items: list[_Item], sort: str, speeds: Mapping[uuid.UUID, int]) -> list[_Item]:
    """By ``sort``, then the newest, then by id. Stable sorts, last key first."""
    key = _ORDER[sort.lstrip("-")]
    ordered = sorted(items, key=lambda item: item.id)
    ordered.sort(key=lambda item: item.created_at, reverse=True)
    ordered.sort(key=lambda item: key(item, speeds), reverse=sort.startswith("-"))
    return ordered


def _download_item(row: Download, title: str) -> _Item:
    total = row.total_size or 0
    return _Item(
        type="download",
        id=row.id,
        title=title,
        status=row.status,
        created_at=row.created_at,
        total_size=row.total_size,
        downloaded_size=row.downloaded_size,
        progress=min(100, row.downloaded_size * 100 // total) if total > 0 else 0,
    )


def _collection_item(row: Download, title: str, members: Sequence[tuple[DownloadStatus, int | None, int]]) -> _Item:
    """Progress is videos done over videos, as the collection's own schema has it: sizes aren't known until each starts."""
    statuses = [status for status, _, _ in members]
    known = [total for _, total, _ in members if total is not None]
    return _Item(
        type="collection",
        id=row.id,
        title=title,
        status=collection_status(statuses),
        created_at=row.created_at,
        total_size=sum(known) if known else None,
        downloaded_size=sum(done for _, _, done in members),
        progress=sum(status in DONE_STATUSES for status in statuses) * 100 // len(statuses) if statuses else 0,
    )


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

    async def claim_next(self, exclude: Collection[uuid.UUID] = ()) -> Download | None:
        """Postgres arbitrates the queue.

        ``FOR UPDATE SKIP LOCKED`` inside a transaction lets several worker
        coroutines pull from one table without ever handing the same row to two
        of them. The status flip commits with the lock.
        """
        async with in_transaction() as conn:
            runnable = Download.filter(
                _not_torrent(), NOT_CONTAINER, status=DownloadStatus.PENDING, deleted_at__isnull=True
            ).filter(Q(next_attempt_at__isnull=True) | Q(next_attempt_at__lte=now()))
            if exclude:
                runnable = runnable.exclude(id__in=list(exclude))
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

    async def end_try(
        self,
        download_id: uuid.UUID,
        fields: Mapping[str, Any],
        *,
        over: Collection[DownloadStatus] = ACTIVE_STATUSES,
    ) -> bool:
        # One conditional UPDATE: nothing can land between a read and the write.
        written = await Download.filter(id=download_id, status__in=list(over), deleted_at__isnull=True).update(**fields)
        return written > 0

    async def flush_progress(self, download_id: uuid.UUID, *, downloaded_size: int, total_size: int | None) -> None:
        await Download.filter(id=download_id).update(downloaded_size=downloaded_size, total_size=total_size)

    @staticmethod
    async def _items(category: uuid.UUID | None) -> list[_Item]:
        """Every top-level row as a list item: standalone downloads and collections, not a collection's videos.

        Three queries whatever the size: the rows with what titles them, the
        first file of those still untitled, and every collection's videos.
        """
        query = Download.filter(parent_id__isnull=True, deleted_at__isnull=True)
        if category is not None:
            query = query.filter(category_id=category)
        rows = await query.prefetch_related(*RELATED)

        untitled = [row.id for row in rows if not (row.media and row.media.title) and not _torrent_named(row)]
        first_files: dict[uuid.UUID, str] = {}
        if untitled:
            for download_id, filename in (
                await File.filter(download_id__in=untitled).order_by("index").values_list("download_id", "filename")
            ):
                first_files.setdefault(download_id, filename)

        collections = [row.id for row in rows if row.media and row.media.kind in CONTAINER_KINDS]
        members: dict[uuid.UUID, list[tuple[DownloadStatus, int | None, int]]] = {id: [] for id in collections}
        if collections:
            for parent_id, status, total, done in await Download.filter(
                parent_id__in=collections, deleted_at__isnull=True
            ).values_list("parent_id", "status", "total_size", "downloaded_size"):
                members[parent_id].append((DownloadStatus(status), total, done))

        items = []
        for row in rows:
            named = [SimpleNamespace(filename=first_files[row.id])] if row.id in first_files else ()
            title = describe(row, named).title
            items.append(
                _collection_item(row, title, members[row.id]) if row.id in members else _download_item(row, title)
            )
        return items

    async def list_items(
        self,
        page: int,
        page_size: int,
        statuses: Sequence[DownloadStatus] | None,
        sort: str,
        speeds: Mapping[uuid.UUID, int],
        category: uuid.UUID | None = None,
    ) -> tuple[list[tuple[str, uuid.UUID]], Meta]:
        items = await self._items(category)
        if statuses:
            wanted = set(statuses)
            items = [item for item in items if item.status in wanted]
        start = (page - 1) * page_size
        shown = _ordered(items, sort, speeds)[start : start + page_size]
        total = len(items)
        meta = Meta(page=page, page_size=page_size, total=total, total_pages=max(1, math.ceil(total / page_size)))
        return [(item.type, item.id) for item in shown], meta

    async def summary(self, category: uuid.UUID | None = None) -> DownloadSummarySchema:
        """Counted from the list's items, so a collection counts once, by its computed status.

        Counted from every row rather than the ones a browser holds, so the
        numbers stay right however little of the list has been loaded.
        """
        counts = Counter(item.status for item in await self._items(category))

        def group(name: str) -> int:
            return sum(counts[status] for status in DOWNLOAD_GROUPS[name])

        return DownloadSummarySchema(
            all=sum(counts.values()),
            downloading=group("downloading"),
            seeding=group("seeding"),
            completed=group("completed"),
        )

    async def set_category(self, download_id: uuid.UUID, category_id: uuid.UUID, folder: str | None) -> None:
        changes: dict[str, Any] = {"category_id": category_id}
        if folder is not None:
            changes["folder"] = folder
        await Download.filter(id=download_id).update(**changes)

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

    async def held_at(self, url: str) -> Download | None:
        held = Download.filter(
            NOT_CONTAINER,
            mirrors__source__url__normalized_hash=address_hash(url),
            deleted_at__isnull=True,
        ).order_by("created_at")
        # A standalone download speaks for the address before a collection's video does:
        # it is a row of the list itself, which a client can select as it stands.
        standalone = await held.filter(parent_id__isnull=True).prefetch_related(*RELATED).first()
        if standalone is not None:
            return standalone
        return await held.filter(parent_id__not_isnull=True).prefetch_related(*RELATED, "parent__media").first()

    async def by_info_hash(self, info_hash: str) -> Download | None:
        return await (
            Download.filter(mirrors__source__torrents__info_hash=info_hash, deleted_at__isnull=True)
            .prefetch_related(*RELATED)
            .first()
        )
