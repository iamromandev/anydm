"""One throughput cap shared by every HTTP download worker and segment.

Pacing happens on the read side: a chunk is admitted before it is written, so
the next read waits, the socket's receive window fills, and TCP slows the
sender. The limiter never touches the network itself.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable
from typing import Protocol


class Limiter(Protocol):
    async def acquire(self, size: int) -> None:
        """Return once ``size`` bytes may be written."""
        ...


class Unlimited:
    """The limiter for a cap of ``0``. Returns without ever suspending."""

    async def acquire(self, size: int) -> None:
        return None


class RateLimiter:
    """A token bucket, kept as the time at which the bucket is next empty.

    Each caller reserves its bytes by pushing ``_ready_at`` forward, then sleeps
    until its reservation comes due. Reading and moving ``_ready_at`` happen
    with no ``await`` between them, so concurrent callers queue in arrival
    order without a lock.

    Idle time banks at most ``burst_s`` of allowance. Without that ceiling an
    hour of silence would admit an hour's worth of bytes at full line speed.

    A caller cancelled mid-sleep keeps its reservation: the bytes it never read
    delay the next caller slightly. That errs toward staying under the cap.
    """

    def __init__(
        self,
        rate_bps: int,
        *,
        burst_s: float = 0.25,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        if rate_bps <= 0:
            raise ValueError("rate_bps must be positive; use Unlimited for no cap")
        self.rate_bps = rate_bps
        self.burst_s = burst_s
        self._clock = clock
        self._sleep = sleep
        self._ready_at = float("-inf")

    async def acquire(self, size: int) -> None:
        now = self._clock()
        self._ready_at = max(self._ready_at, now - self.burst_s) + size / self.rate_bps
        wait = self._ready_at - now
        if wait > 0:
            await self._sleep(wait)


def rate_limiter(rate_bps: int) -> Limiter:
    """``0`` is unlimited, which keeps the hot path free of any sleep."""
    return Unlimited() if rate_bps == 0 else RateLimiter(rate_bps)
