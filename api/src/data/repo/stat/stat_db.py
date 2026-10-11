from __future__ import annotations

import uuid
from collections.abc import Collection
from datetime import date, datetime

from tortoise.expressions import F, Q

from src.data.db.model import Download, Speed, Traffic
from src.data.repo.download.described import primary_mirror
from src.data.repo.download.interface.download import NOT_CONTAINER
from src.data.repo.stat.interface import DownloadSnapshot, SpeedRow, StatRepo, TrafficDelta, TrafficRow
from src.data.type import DownloadStatus, SourceKind

#: The statuses in which bytes move: a download, its parts being joined, a torrent sharing.
_MOVING = (DownloadStatus.DOWNLOADING, DownloadStatus.MUXING, DownloadStatus.SEEDING)
#: Read every tick too: a failure is a state a download rests in, so a quick one is still there to be seen.
_READ = (*_MOVING, DownloadStatus.FAILED)


class StatDatabaseRepo(StatRepo):
    async def snapshots(self, watching: Collection[uuid.UUID], since: datetime) -> list[DownloadSnapshot]:
        rows = (
            await Download.filter(NOT_CONTAINER)
            .filter(
                Q(status__in=list(_READ))
                | Q(id__in=list(watching))
                | Q(created_at__gte=since)
                | Q(completed_at__gte=since)
                | Q(deleted_at__gte=since)
            )
            .prefetch_related("mirrors__source")
        )
        snapshots = []
        for row in rows:
            mirror = primary_mirror(row)
            if mirror is None:
                continue
            snapshots.append(
                DownloadSnapshot(
                    id=row.id,
                    status=row.status,
                    downloaded_size=row.downloaded_size,
                    uploaded_size=row.uploaded_size,
                    created_at=row.created_at,
                    completed_at=row.completed_at,
                    deleted_at=row.deleted_at,
                    kind=mirror.source.kind,
                    provider_id=mirror.source.provider_id,
                )
            )
        return snapshots

    async def add_traffic(self, day: date, kind: SourceKind, provider_id: uuid.UUID, delta: TrafficDelta) -> None:
        row, _ = await Traffic.get_or_create(day=day, kind=kind, provider_id=provider_id)
        await Traffic.filter(id=row.id).update(
            downloaded_size=F("downloaded_size") + delta.downloaded_size,
            uploaded_size=F("uploaded_size") + delta.uploaded_size,
            finished=F("finished") + delta.finished,
            failed=F("failed") + delta.failed,
            removed=F("removed") + delta.removed,
        )

    async def add_speed(self, hour: datetime, bps: int) -> None:
        row, _ = await Speed.get_or_create(hour=hour)
        # One recorder writes this table, so reading the highest back is safe.
        await Speed.filter(id=row.id).update(
            total_bps=F("total_bps") + bps,
            samples=F("samples") + 1,
            maximum_bps=max(row.maximum_bps, bps),
        )

    async def prune_speed(self, before: datetime) -> int:
        return await Speed.filter(hour__lt=before).delete()

    async def traffic(self, start: date, end: date) -> list[TrafficRow]:
        rows = await Traffic.filter(day__gte=start, day__lte=end).select_related("provider").order_by("day")
        return [
            TrafficRow(
                day=row.day,
                kind=row.kind,
                provider_slug=row.provider.slug if row.provider else None,
                provider_name=row.provider.name if row.provider else None,
                delta=TrafficDelta(row.downloaded_size, row.uploaded_size, row.finished, row.failed, row.removed),
            )
            for row in rows
        ]

    async def speeds(self, start: datetime, end: datetime) -> list[SpeedRow]:
        rows = await Speed.filter(hour__gte=start, hour__lt=end).order_by("hour")
        return [SpeedRow(row.hour, row.total_bps, row.maximum_bps, row.samples) for row in rows]
