from __future__ import annotations

from datetime import date, datetime

from src.core.base import BaseSchema
from src.data.type import StatGroup


class StatGroupSchema(BaseSchema):
    """What moved in one group: a day, a kind of source, or a provider."""

    #: The day (``2026-10-11``), the kind (``direct``, ``site``, ``torrent``), or the provider's slug.
    key: str
    #: The provider's name; none for a day or a kind, and for traffic whose provider was deleted.
    name: str | None = None
    downloaded_size: int = 0
    uploaded_size: int = 0
    finished: int = 0
    failed: int = 0
    removed: int = 0


class SpeedPointSchema(BaseSchema):
    #: The start of the UTC hour.
    hour: datetime
    average_bps: int
    maximum_bps: int


class StatsSchema(BaseSchema):
    group: StatGroup
    start: date
    end: date
    groups: list[StatGroupSchema]
    #: Hourly speed over the same days, for the hours something was downloading; kept 30 days.
    speeds: list[SpeedPointSchema]
