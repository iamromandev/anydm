"""An in-memory ``StatRepo``: what the recorder and the stats service are tested against."""

import uuid
from collections.abc import Collection
from dataclasses import replace
from datetime import date, datetime

from src.data.repo.stat import DownloadSnapshot, SpeedRow, StatRepo, TrafficDelta, TrafficRow
from src.data.type import SourceKind


class FakeStatRepo(StatRepo):
    def __init__(self) -> None:
        #: What ``snapshots`` answers: the test sets the downloads as the database would hold them.
        self.downloads: list[DownloadSnapshot] = []
        self.traffic_rows: dict[tuple[date, SourceKind, uuid.UUID], TrafficDelta] = {}
        self.speed_rows: dict[datetime, SpeedRow] = {}
        self.pruned: list[datetime] = []
        self.watched: list[set[uuid.UUID]] = []
        #: Raised by the next ``add_traffic``, once.
        self.fail_next_write: Exception | None = None
        self.providers: dict[uuid.UUID, tuple[str, str]] = {}

    def put(self, snapshot: DownloadSnapshot) -> None:
        self.downloads = [row for row in self.downloads if row.id != snapshot.id] + [snapshot]

    def change(self, id: uuid.UUID, **fields: object) -> None:
        self.downloads = [replace(row, **fields) if row.id == id else row for row in self.downloads]

    async def snapshots(self, watching: Collection[uuid.UUID], since: datetime) -> list[DownloadSnapshot]:
        self.watched.append(set(watching))
        return list(self.downloads)

    async def add_traffic(self, day: date, kind: SourceKind, provider_id: uuid.UUID, delta: TrafficDelta) -> None:
        if self.fail_next_write is not None:
            error, self.fail_next_write = self.fail_next_write, None
            raise error
        key = (day, kind, provider_id)
        self.traffic_rows[key] = self.traffic_rows.get(key, TrafficDelta()) + delta

    async def add_speed(self, hour: datetime, bps: int) -> None:
        old = self.speed_rows.get(hour, SpeedRow(hour, 0, 0, 0))
        self.speed_rows[hour] = SpeedRow(hour, old.total_bps + bps, max(old.maximum_bps, bps), old.samples + 1)

    async def prune_speed(self, before: datetime) -> int:
        self.pruned.append(before)
        gone = [hour for hour in self.speed_rows if hour < before]
        for hour in gone:
            del self.speed_rows[hour]
        return len(gone)

    async def traffic(self, start: date, end: date) -> list[TrafficRow]:
        return [
            TrafficRow(day, kind, *self.providers.get(provider_id, (None, None)), delta)
            for (day, kind, provider_id), delta in sorted(self.traffic_rows.items(), key=lambda item: item[0][0])
            if start <= day <= end
        ]

    async def speeds(self, start: datetime, end: datetime) -> list[SpeedRow]:
        return sorted((row for hour, row in self.speed_rows.items() if start <= hour < end), key=lambda r: r.hour)
