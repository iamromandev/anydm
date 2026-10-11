from __future__ import annotations

from enum import StrEnum


class StatGroup(StrEnum):
    """What ``GET /stats`` adds the traffic up by."""

    DAY = "day"
    KIND = "kind"
    PROVIDER = "provider"
