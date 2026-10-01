from __future__ import annotations

import uuid
from collections.abc import Sequence

from src.data.db.model import Queue
from src.data.repo.download.interface.queue import QueueRepo, QueueRow
from src.data.type import MAIN_QUEUE


class QueueDatabaseRepo(QueueRepo):
    async def list_all(self) -> list[Queue]:
        return await Queue.all().order_by("position", "name")

    async def get(self, queue_id: uuid.UUID) -> Queue | None:
        return await Queue.filter(id=queue_id).first()

    async def main(self) -> Queue:
        return await Queue.get(name=MAIN_QUEUE)

    async def insert_missing(self, rows: Sequence[QueueRow]) -> int:
        have = set(await Queue.all().values_list("name", flat=True))
        fresh = [
            Queue(name=row.name, max_concurrent=row.max_concurrent, position=row.position)
            for row in rows
            if row.name not in have
        ]
        if fresh:
            await Queue.bulk_create(fresh)
        return len(fresh)
