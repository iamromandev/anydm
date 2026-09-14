from __future__ import annotations

from datetime import UTC, datetime

from src.core.common import serialize

__all__ = ["serialize", "utc_iso_timestamp"]


def utc_iso_timestamp() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")
