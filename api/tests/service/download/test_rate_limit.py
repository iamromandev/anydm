import asyncio
import time

import pytest
from src.service.download.rate_limit import RateLimiter, Unlimited, rate_limiter


class _FakeClock:
    """Time that only moves when the limiter sleeps."""

    def __init__(self) -> None:
        self.now = 0.0
        self.sleeps: list[float] = []

    def __call__(self) -> float:
        return self.now

    async def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


def _limiter(rate_bps: int, clock: _FakeClock) -> RateLimiter:
    return RateLimiter(rate_bps, clock=clock, sleep=clock.sleep)


@pytest.mark.asyncio
async def test_a_megabyte_at_256_kb_per_second_takes_at_least_3_5_seconds() -> None:
    clock = _FakeClock()
    limiter = _limiter(256 * 1024, clock)

    for _ in range(16):
        await limiter.acquire(64 * 1024)

    assert clock.now >= 3.5


@pytest.mark.asyncio
async def test_idle_time_banks_only_a_short_burst() -> None:
    clock = _FakeClock()
    limiter = _limiter(1000, clock)
    clock.now = 3600.0  # an hour of silence must not buy an hour of full speed

    for _ in range(10):
        await limiter.acquire(1000)

    assert clock.now - 3600.0 >= 9.0


@pytest.mark.asyncio
async def test_a_read_within_the_banked_burst_does_not_sleep() -> None:
    clock = _FakeClock()
    limiter = _limiter(1000, clock)
    clock.now = 10.0

    await limiter.acquire(100)

    assert clock.sleeps == []


@pytest.mark.asyncio
async def test_a_chunk_larger_than_one_second_of_allowance_is_still_admitted() -> None:
    clock = _FakeClock()
    limiter = _limiter(1000, clock)

    await limiter.acquire(5000)

    assert clock.now == pytest.approx(5.0 - limiter.burst_s)


def test_the_unlimited_limiter_never_suspends() -> None:
    coroutine = Unlimited().acquire(10**9)

    # A coroutine that finishes on its first step never yielded to the loop.
    with pytest.raises(StopIteration):
        coroutine.send(None)


def test_zero_means_unlimited() -> None:
    assert isinstance(rate_limiter(0), Unlimited)
    assert isinstance(rate_limiter(1024), RateLimiter)


def test_a_negative_rate_is_refused() -> None:
    with pytest.raises(ValueError):
        RateLimiter(-1)


@pytest.mark.asyncio
async def test_concurrent_readers_share_one_cap() -> None:
    # Real time on purpose: the fake clock adds every caller's sleep to one
    # counter, which would overstate the elapsed time and prove nothing.
    rate = 200_000
    limiter = RateLimiter(rate)

    async def reader(total: int) -> None:
        for _ in range(total // 10_000):
            await limiter.acquire(10_000)

    started = time.monotonic()
    await asyncio.gather(reader(150_000), reader(150_000))
    elapsed = time.monotonic() - started

    assert elapsed >= 300_000 / rate - limiter.burst_s
