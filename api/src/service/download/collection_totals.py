"""A collection's status and totals, computed from its downloads when read.

Nothing is written back: the old group row's stored totals drifted, and cost a
write per member change. ``refresh`` recomputes and publishes a ``collection``
frame whenever a member's status changes (never on a progress tick).
"""

from __future__ import annotations

import uuid
from collections.abc import Iterable, Sequence
from typing import Any

from src.data.repo.download.interface import CollectionRepo, MemberRow
from src.data.schema.download import CollectionCountsSchema, CollectionSchema
from src.data.type import CollectionKind, DownloadStatus, MediaKind
from src.lib.event import EventHub
from src.service.download.live import LiveStats
from src.service.download.paths import collection_folder

#: Statuses that mean "still to do": queued or downloading.
_ACTIVE = frozenset({DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.MUXING})


def collection_status(statuses: Iterable[DownloadStatus]) -> DownloadStatus:
    """Downloading while anything is left to do, then paused, failed or complete."""
    seen = set(statuses)
    if seen & _ACTIVE:
        return DownloadStatus.DOWNLOADING
    if DownloadStatus.PAUSED in seen:
        return DownloadStatus.PAUSED
    if DownloadStatus.FAILED in seen:
        return DownloadStatus.FAILED
    return DownloadStatus.COMPLETED


def counts_of(rows: Sequence[MemberRow]) -> CollectionCountsSchema:
    statuses = [row[1] for row in rows]
    return CollectionCountsSchema(
        total=len(statuses),
        complete=sum(s in (DownloadStatus.COMPLETED, DownloadStatus.SEEDING) for s in statuses),
        active=sum(s in _ACTIVE for s in statuses),
        downloading=sum(s in (DownloadStatus.DOWNLOADING, DownloadStatus.MUXING) for s in statuses),
        paused=statuses.count(DownloadStatus.PAUSED),
        failed=statuses.count(DownloadStatus.FAILED),
    )


class CollectionTotals:
    def __init__(self, repo: CollectionRepo, hub: EventHub, live: LiveStats) -> None:
        self._repo = repo
        self._hub = hub
        self._live = live

    async def schema(self, collection: Any, *, watched: int | None = None) -> CollectionSchema:
        rows = await self._repo.member_rows(collection.id)
        counts = counts_of(rows)
        counts.watched = watched
        known = [total for _, _, _, total in rows if total is not None]
        return CollectionSchema(
            id=collection.id,
            kind=CollectionKind.CHANNEL if collection.media_kind == MediaKind.CHANNEL else CollectionKind.PLAYLIST,
            extractor=collection.provider,
            external_id=collection.ref_id,
            title=collection.title,
            folder=collection_folder(collection.title, collection.ref_id),
            preset=collection.site_detail.preset,
            status=collection_status(row[1] for row in rows),
            # Videos done over videos: sizes aren't known until each starts.
            progress=counts.complete * 100 // counts.total if counts.total else 0,
            counts=counts,
            total_bytes=sum(known) if known else None,
            downloaded_bytes=sum(done for _, _, done, _ in rows),
            speed_bps=sum(self._live.get(member).speed_bps for member, _, _, _ in rows),
            created_at=collection.created_at,
        )

    async def schemas(self, collections: Sequence[Any]) -> list[CollectionSchema]:
        """For the list: each with its watched count, read in one query."""
        watched = await self._repo.watched_counts([c.id for c in collections])
        return [await self.schema(c, watched=watched.get(c.id, 0)) for c in collections]

    async def refresh(self, collection_id: uuid.UUID) -> CollectionSchema | None:
        """Recompute and publish one ``collection`` frame; ``None`` once it's gone."""
        collection = await self._repo.get_active_by_id(collection_id)
        if collection is None:
            return None
        schema = await self.schema(collection)
        self._hub.publish("collection", schema.to_json())
        return schema
