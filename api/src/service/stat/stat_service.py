from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, date, datetime, time, timedelta

from src.core.common import now
from src.core.error import Error
from src.data.repo.stat import StatRepo, TrafficDelta
from src.data.schema.audit import SpeedPointSchema, StatGroupSchema, StatsSchema
from src.data.type import StatGroup

#: Without ``from``, the last 30 days ending today.
DEFAULT_DAYS = 30
#: The widest range one request may ask for.
MAX_DAYS = 366

#: The key of traffic whose provider has been deleted.
_UNKNOWN = "unknown"


class StatService:
    def __init__(self, repo: StatRepo, clock: Callable[[], datetime] = now) -> None:
        self._repo = repo
        self._clock = clock

    async def stats(self, start: date | None, end: date | None, group: StatGroup) -> StatsSchema:
        end = end or self._clock().date()
        start = start or end - timedelta(days=DEFAULT_DAYS - 1)
        if start > end:
            raise Error.bad_request("'from' must not be after 'to'")
        if (end - start).days + 1 > MAX_DAYS:
            raise Error.bad_request(f"A range may cover at most {MAX_DAYS} days")

        totals: dict[str, tuple[str | None, TrafficDelta]] = {}
        for row in await self._repo.traffic(start, end):
            key, name = _key(row.day, row.kind, row.provider_slug, row.provider_name, group)
            totals[key] = (name, totals.get(key, (name, TrafficDelta()))[1] + row.delta)

        speeds = await self._repo.speeds(
            datetime.combine(start, time.min, tzinfo=UTC),
            datetime.combine(end + timedelta(days=1), time.min, tzinfo=UTC),
        )
        return StatsSchema(
            group=group,
            start=start,
            end=end,
            groups=[
                StatGroupSchema(
                    key=key,
                    name=name,
                    downloaded_size=delta.downloaded_size,
                    uploaded_size=delta.uploaded_size,
                    finished=delta.finished,
                    failed=delta.failed,
                    removed=delta.removed,
                )
                for key, (name, delta) in sorted(totals.items())
            ],
            speeds=[
                SpeedPointSchema(
                    hour=row.hour,
                    average_bps=row.total_bps // row.samples if row.samples else 0,
                    maximum_bps=row.maximum_bps,
                )
                for row in speeds
            ],
        )


def _key(day: date, kind: object, slug: str | None, name: str | None, group: StatGroup) -> tuple[str, str | None]:
    if group == StatGroup.DAY:
        return day.isoformat(), None
    if group == StatGroup.KIND:
        return str(kind), None
    return (slug or _UNKNOWN), name
