"""In-process signalling between the API and the workers.

Both live in one process, which is what makes this an ``asyncio.Event`` and a
set rather than a table or a message broker. Enqueue wakes a worker in
microseconds, and a pause reaches a running transfer between chunks.
"""

from __future__ import annotations

import asyncio
import contextlib
import uuid


class DownloadControl:
    def __init__(self) -> None:
        self._work = asyncio.Event()
        self._stopping: set[uuid.UUID] = set()

    def wake(self) -> None:
        """Tell an idle worker there is something to claim."""
        self._work.set()

    async def wait_for_work(self, timeout: float) -> None:
        """Block until woken or ``timeout`` elapses, then consume the signal.

        The timeout is the fallback poll: it covers a row that appeared without
        going through enqueue, such as one a retry backoff has just made
        runnable.
        """
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(self._work.wait(), timeout=timeout)
        self._work.clear()

    def request_stop(self, task_id: uuid.UUID) -> None:
        self._stopping.add(task_id)

    def clear_stop(self, task_id: uuid.UUID) -> None:
        self._stopping.discard(task_id)

    def is_stopping(self, task_id: uuid.UUID) -> bool:
        return task_id in self._stopping
