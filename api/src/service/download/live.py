"""The numbers that move every tick, kept in memory and never stored.

Speed, ETA, upload speed and peers are wrong the moment the process restarts,
so writing them to a row on every tick bought a database write for nothing.
After a restart every download reads as still until its first tick, which is
the truth.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Live:
    speed_bps: int = 0
    eta_seconds: int | None = None
    upload_speed_bps: int = 0
    peers: int = 0


_STILL = Live()


class LiveStats:
    """One per process, shared by the worker pool, the torrent monitor and the API."""

    def __init__(self) -> None:
        self._by_id: dict[uuid.UUID, Live] = {}

    def set(self, download_id: uuid.UUID, live: Live) -> None:
        self._by_id[download_id] = live

    def get(self, download_id: uuid.UUID) -> Live:
        return self._by_id.get(download_id, _STILL)

    def clear(self, download_id: uuid.UUID) -> None:
        self._by_id.pop(download_id, None)

    def speeds(self) -> dict[uuid.UUID, int]:
        """Every download moving right now, with its speed: what sorting by speed joins."""
        return {download_id: live.speed_bps for download_id, live in self._by_id.items() if live.speed_bps > 0}
