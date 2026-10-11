from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Collection
from dataclasses import dataclass
from datetime import date, datetime

from src.data.type import DownloadStatus, SourceKind


@dataclass(frozen=True, slots=True)
class DownloadSnapshot:
    """What the recorder reads of one download: its counters and the way it came in."""

    id: uuid.UUID
    status: DownloadStatus
    downloaded_size: int
    uploaded_size: int
    created_at: datetime
    completed_at: datetime | None
    deleted_at: datetime | None
    kind: SourceKind
    provider_id: uuid.UUID

    @property
    def removed(self) -> bool:
        """Removed by the person: ``CANCELLED``, or soft-deleted."""
        return self.status == DownloadStatus.CANCELLED or self.deleted_at is not None


@dataclass(frozen=True, slots=True)
class TrafficDelta:
    downloaded_size: int = 0
    uploaded_size: int = 0
    finished: int = 0
    failed: int = 0
    removed: int = 0

    def __add__(self, other: TrafficDelta) -> TrafficDelta:
        return TrafficDelta(
            self.downloaded_size + other.downloaded_size,
            self.uploaded_size + other.uploaded_size,
            self.finished + other.finished,
            self.failed + other.failed,
            self.removed + other.removed,
        )

    def __bool__(self) -> bool:
        return any((self.downloaded_size, self.uploaded_size, self.finished, self.failed, self.removed))


@dataclass(frozen=True, slots=True)
class TrafficRow:
    """One stored ``audit.traffic`` row, with its provider's name when it still has one."""

    day: date
    kind: SourceKind
    provider_slug: str | None
    provider_name: str | None
    delta: TrafficDelta


@dataclass(frozen=True, slots=True)
class SpeedRow:
    hour: datetime
    total_bps: int
    maximum_bps: int
    samples: int


class StatRepo(ABC):
    @abstractmethod
    async def snapshots(self, watching: Collection[uuid.UUID], since: datetime) -> list[DownloadSnapshot]:
        """The downloads worth reading now: those moving bytes (downloading, muxing, seeding), those failed, those in
        ``watching``, and those created, completed or removed since ``since``. Containers hold no bytes of their own
        and are left out, as is any download without a source."""
        ...

    @abstractmethod
    async def add_traffic(self, day: date, kind: SourceKind, provider_id: uuid.UUID, delta: TrafficDelta) -> None:
        """Add ``delta`` to the row for that day, kind and provider, making it the first time."""
        ...

    @abstractmethod
    async def add_speed(self, hour: datetime, bps: int) -> None:
        """Take one reading into the row for that hour: add it to the sum, count it, keep the highest."""
        ...

    @abstractmethod
    async def prune_speed(self, before: datetime) -> int:
        """Delete the hours that start before ``before``; how many went."""
        ...

    @abstractmethod
    async def traffic(self, start: date, end: date) -> list[TrafficRow]:
        """Every traffic row from ``start`` to ``end``, both days included."""
        ...

    @abstractmethod
    async def speeds(self, start: datetime, end: datetime) -> list[SpeedRow]:
        """The hours that start in ``[start, end)``, oldest first."""
        ...
