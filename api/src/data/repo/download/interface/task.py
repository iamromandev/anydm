from __future__ import annotations

import uuid
from abc import abstractmethod

from src.core.base import CrudRepo
from src.core.success import Meta
from src.data.db.model import Task


class TaskRepo(CrudRepo[Task]):
    @abstractmethod
    async def claim_next(self) -> Task | None:
        """Take the oldest runnable pending task and mark it ``downloading``.

        Runnable means ``next_attempt_at`` is unset or already past. Returns
        ``None`` when the queue is empty.
        """
        ...

    @abstractmethod
    async def recover_orphans(self) -> int:
        """Requeue every task left mid-flight by a dead process. Returns the count."""
        ...

    @abstractmethod
    async def flush_progress(
        self,
        task_id: uuid.UUID,
        *,
        downloaded_bytes: int,
        total_bytes: int | None,
        progress: int,
        speed_bps: int,
        eta_seconds: int | None,
    ) -> None:
        """Write one progress sample and stamp ``heartbeat_at``."""
        ...

    @abstractmethod
    async def list_page(self, page: int, page_size: int) -> tuple[list[Task], Meta]:
        ...

    @abstractmethod
    async def get_active_by_id(self, task_id: uuid.UUID) -> Task | None:
        ...
