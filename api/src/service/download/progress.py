"""Download progress arithmetic. Pure — the clock is a parameter.

The throttle lives here rather than in the downloader because it is the part
worth testing: a 4 GB file at 20 MB/s produces thousands of chunk callbacks a
second, and every one of them must not become a database write.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ProgressSample:
    downloaded_bytes: int
    total_bytes: int | None
    progress: int
    speed_bps: int
    eta_seconds: int | None


class ProgressTracker:
    def __init__(
        self,
        *,
        total_bytes: int | None,
        initial_bytes: int = 0,
        flush_interval_ms: int = 1000,
        alpha: float = 0.3,
        started_at: float = 0.0,
    ) -> None:
        self._total = total_bytes
        self._downloaded = initial_bytes
        self._interval = flush_interval_ms / 1000
        self._alpha = alpha
        self._speed = 0.0
        self._seeded = False
        self._last_flush_at = started_at
        self._bytes_at_last_flush = initial_bytes

    def record(self, chunk_len: int, at: float) -> ProgressSample | None:
        """Count ``chunk_len`` bytes; return a sample only when one is due."""
        self._downloaded += chunk_len
        if at - self._last_flush_at < self._interval:
            return None
        return self._flush(at)

    def snapshot(self, at: float) -> ProgressSample:
        """A sample now, whatever the throttle says — for the final write."""
        return self._flush(at)

    def _flush(self, at: float) -> ProgressSample:
        elapsed = at - self._last_flush_at
        if elapsed > 0:
            instant = (self._downloaded - self._bytes_at_last_flush) / elapsed
            # The first sample seeds the average. Smoothing it against an
            # initial zero would report roughly a third of the true speed for
            # the first several seconds of every download.
            self._speed = instant if not self._seeded else self._alpha * instant + (1 - self._alpha) * self._speed
            self._seeded = True

        self._last_flush_at = at
        self._bytes_at_last_flush = self._downloaded

        return ProgressSample(
            downloaded_bytes=self._downloaded,
            total_bytes=self._total,
            progress=self._percent(),
            speed_bps=int(self._speed),
            eta_seconds=self._eta(),
        )

    def _percent(self) -> int:
        if not self._total:
            return 0
        return min(100, self._downloaded * 100 // self._total)

    def _eta(self) -> int | None:
        if not self._total or self._speed <= 0:
            return None
        remaining = max(0, self._total - self._downloaded)
        return int(remaining / self._speed)
