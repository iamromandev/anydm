"""Give the Main queue a row where it has none.

Run at startup, the way search sources are seeded. Never overwrites a row a
person has edited: only missing names are inserted.
"""

from __future__ import annotations

from src.data.repo.download.interface.queue import QueueRepo, QueueRow
from src.data.type import MAIN_QUEUE


async def seed_organization(queues: QueueRepo, *, workers: int) -> int:
    """Insert the missing Main queue. Returns how many were added."""
    return await queues.insert_missing([QueueRow(MAIN_QUEUE, "main", max(1, workers), 0, is_default=True)])
