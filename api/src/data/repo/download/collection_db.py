from __future__ import annotations

import math
import uuid
from collections.abc import Sequence
from typing import Any, cast

from tortoise.backends.base.client import BaseDBAsyncClient
from tortoise.transactions import in_transaction

from src.core.common import now
from src.core.success import Meta
from src.data.db.model import Collection, Download, DownloadFile, PlaybackPosition, Queue, SiteDetail
from src.data.repo.download import transitions
from src.data.repo.download.interface.collection import CollectionRepo, EntryRow, MemberRow
from src.data.repo.download.interface.download import RELATED
from src.data.type import MAIN_QUEUE, DownloadStatus


class CollectionDatabaseRepo(CollectionRepo):
    async def find(self, extractor: str, external_id: str) -> Collection | None:
        return await Collection.filter(extractor=extractor, external_id=external_id, deleted_at__isnull=True).first()

    async def get_active_by_id(self, collection_id: uuid.UUID) -> Collection | None:
        return await Collection.filter(id=collection_id, deleted_at__isnull=True).first()

    async def by_ids(self, ids: Sequence[uuid.UUID]) -> list[Collection]:
        if not ids:
            return []
        found = {row.id: row for row in await Collection.filter(id__in=list(ids))}
        return [found[collection_id] for collection_id in ids if collection_id in found]

    async def create_with_entries(self, collection: dict[str, Any], entries: Sequence[EntryRow]) -> Collection:
        async with in_transaction() as conn:
            row = await Collection.create(using_db=conn, **collection)
            await self._insert(row.id, entries, conn)
        return row

    async def add_entries(self, collection: Collection, entries: Sequence[EntryRow]) -> None:
        async with in_transaction() as conn:
            await self._insert(collection.id, entries, conn)

    @staticmethod
    async def _insert(collection_id: uuid.UUID, entries: Sequence[EntryRow], conn: BaseDBAsyncClient) -> None:
        """Three bulk inserts however long the listing: 5,000 videos must not be 15,000 statements."""
        if not entries:
            return
        main = await Queue.get(name=MAIN_QUEUE, using_db=conn)
        start = await transitions.next_queue_position(main.id, conn)
        downloads = [
            Download(
                **{"queue_id": main.id, **entry.download},
                collection_id=collection_id,
                queue_position=start + offset,
            )
            for offset, entry in enumerate(entries)
        ]
        await Download.bulk_create(downloads, batch_size=500, using_db=conn)
        await SiteDetail.bulk_create(
            [SiteDetail(download_id=row.id, **entry.site) for row, entry in zip(downloads, entries, strict=True)],
            batch_size=500,
            using_db=conn,
        )
        await DownloadFile.bulk_create(
            [
                DownloadFile(download_id=row.id, index=0, path=entry.filename)
                for row, entry in zip(downloads, entries, strict=True)
            ],
            batch_size=500,
            using_db=conn,
        )

    @staticmethod
    def _members(collection_id: uuid.UUID) -> Any:
        return Download.filter(collection_id=collection_id, deleted_at__isnull=True)

    async def downloads_page(
        self, collection_id: uuid.UUID, page: int, page_size: int
    ) -> tuple[list[Download], Meta]:
        query = self._members(collection_id).order_by("position")
        total = await query.count()
        rows = await query.offset((page - 1) * page_size).limit(page_size).prefetch_related(*RELATED)
        meta = Meta(page=page, page_size=page_size, total=total, total_pages=max(1, math.ceil(total / page_size)))
        return rows, meta

    async def member_rows(self, collection_id: uuid.UUID) -> list[MemberRow]:
        rows = await self._members(collection_id).values_list("id", "status", "downloaded_bytes", "total_bytes")
        return [(row_id, DownloadStatus(status), done, total) for row_id, status, done, total in rows]

    async def held(self, collection_id: uuid.UUID) -> dict[str, tuple[uuid.UUID, DownloadStatus, int | None]]:
        rows = cast(
            list[tuple[str, uuid.UUID, str, int | None]],
            await self._members(collection_id).values_list("site_detail__video_id", "id", "status", "position"),
        )
        return {video_id: (row_id, DownloadStatus(status), position) for video_id, row_id, status, position in rows}

    async def pause(self, collection_id: uuid.UUID) -> list[uuid.UUID]:
        return await transitions.pause_rows(self._members(collection_id))

    async def resume(self, collection_id: uuid.UUID) -> int:
        return await transitions.requeue_rows(self._members(collection_id))

    async def remove(self, collection_id: uuid.UUID) -> list[uuid.UUID]:
        members = self._members(collection_id)
        ids = await transitions.ids_of(members, "id")
        await members.update(status=DownloadStatus.CANCELED, deleted_at=now())
        return ids

    @staticmethod
    def _all_members() -> Any:
        return Download.filter(collection_id__isnull=False, deleted_at__isnull=True)

    async def pause_all(self) -> tuple[list[uuid.UUID], set[uuid.UUID]]:
        members = self._all_members()
        touched = set(
            await transitions.ids_of(
                members.filter(status__in=[DownloadStatus.PENDING, DownloadStatus.DOWNLOADING]), "collection_id"
            )
        )
        return await transitions.pause_rows(members), touched

    async def resume_all(self) -> set[uuid.UUID]:
        members = self._all_members()
        touched = set(
            await transitions.ids_of(
                members.filter(status__in=[DownloadStatus.PAUSED, DownloadStatus.FAILED]), "collection_id"
            )
        )
        await transitions.requeue_rows(members)
        return touched

    async def requeue(self, ids: Sequence[uuid.UUID]) -> int:
        if not ids:
            return 0
        return await transitions.requeue_rows(Download.filter(id__in=list(ids), deleted_at__isnull=True))

    async def watched_counts(self, ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, int]:
        counts: dict[uuid.UUID, int] = dict.fromkeys(ids, 0)
        if counts:
            owners = await PlaybackPosition.filter(
                watched=True,
                file__download__collection_id__in=list(counts),
                file__download__deleted_at__isnull=True,
            ).values_list("file__download__collection_id", flat=True)
            for owner in owners:
                counts[cast(uuid.UUID, owner)] += 1
        return counts

    async def soft_delete(self, collection: Collection) -> None:
        collection.deleted_at = now()
        await collection.save(update_fields=["deleted_at"])
