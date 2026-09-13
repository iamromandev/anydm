"""In-process pub/sub for server-sent events.

Workers and SSE subscribers share a process, so a progress update reaches the
browser without a database round trip — this is the thing the in-process worker
pool buys that a separate worker container could not.
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncIterator
from functools import lru_cache
from typing import Any

_DEFAULT_MAX_QUEUE = 100
#: Pushed by ``close()`` so a reader blocked on ``get()`` wakes and stops,
#: rather than hanging until the next unrelated publish.
_CLOSED = "__closed__"


class Subscription:
    def __init__(self, hub: EventHub, max_queue: int) -> None:
        self._hub = hub
        self._queue: asyncio.Queue[tuple[str, Any]] = asyncio.Queue(maxsize=max_queue)
        self._closed = False

    def _offer(self, event: str, data: Any) -> None:
        """Enqueue, dropping the oldest frame when the reader has fallen behind.

        Never blocks and never raises: a slow browser must not be able to hold
        up a download. Progress frames are snapshots, so losing an old one
        costs nothing — the next one carries the current state anyway.
        """
        if self._closed:
            return
        if self._queue.full():
            with contextlib.suppress(asyncio.QueueEmpty):
                self._queue.get_nowait()
        with contextlib.suppress(asyncio.QueueFull):
            self._queue.put_nowait((event, data))

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._hub._remove(self)
        with contextlib.suppress(asyncio.QueueFull):
            self._queue.put_nowait((_CLOSED, None))

    def __aiter__(self) -> AsyncIterator[tuple[str, Any]]:
        return self._iterate()

    async def _iterate(self) -> AsyncIterator[tuple[str, Any]]:
        while True:
            if self._closed and self._queue.empty():
                return
            event, data = await self._queue.get()
            if event == _CLOSED:
                return
            yield event, data


class EventHub:
    def __init__(self, max_queue: int = _DEFAULT_MAX_QUEUE) -> None:
        self._subscribers: set[Subscription] = set()
        self._max_queue = max_queue

    def subscribe(self) -> Subscription:
        subscription = Subscription(self, self._max_queue)
        self._subscribers.add(subscription)
        return subscription

    def publish(self, event: str, data: Any) -> None:
        for subscription in tuple(self._subscribers):
            subscription._offer(event, data)

    def subscriber_count(self) -> int:
        return len(self._subscribers)

    def _remove(self, subscription: Subscription) -> None:
        self._subscribers.discard(subscription)


@lru_cache
def get_event_hub() -> EventHub:
    return EventHub()
