from collections import namedtuple

import pytest
from src.lib.event import EventHub
from src.service.download.disk import DiskGuard
from src.service.download.disk_monitor import DiskMonitor

GIB = 1024**3
_Usage = namedtuple("_Usage", ["total", "used", "free"])


def _guard(free: int = 40 * GIB) -> DiskGuard:
    return DiskGuard("/data", GIB, usage=lambda _path: _Usage(100 * GIB, 0, free))


@pytest.mark.asyncio
async def test_a_tick_publishes_the_disk_to_whoever_is_listening() -> None:
    hub = EventHub()
    subscription = hub.subscribe()

    await DiskMonitor(_guard(), hub).tick()

    event, data = await anext(aiter(subscription))
    assert event == "disk"
    assert data == {
        "path": "/data",
        "total_bytes": 100 * GIB,
        "free_bytes": 40 * GIB,
        "min_free_bytes": GIB,
    }
    subscription.close()


@pytest.mark.asyncio
async def test_nobody_listening_means_nothing_is_read() -> None:
    reads: list[str] = []

    def usage(path: str) -> _Usage:
        reads.append(path)
        return _Usage(1, 0, 1)

    await DiskMonitor(DiskGuard("/data", GIB, usage=usage), EventHub()).tick()

    assert reads == []


@pytest.mark.asyncio
async def test_an_unreadable_disk_publishes_nothing() -> None:
    def unreadable(_path: str) -> _Usage:
        raise FileNotFoundError("/data")

    hub = EventHub()
    subscription = hub.subscribe()

    await DiskMonitor(DiskGuard("/data", GIB, usage=unreadable), hub).tick()

    # Closing ends the iteration after whatever was already queued.
    subscription.close()
    assert [event async for event, _ in subscription] == []
