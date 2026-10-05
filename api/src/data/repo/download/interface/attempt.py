from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any

from src.data.type import AttemptStatus, MirrorStatus

#: Mirror statuses a try may still use.
USABLE_MIRRORS = (MirrorStatus.AVAILABLE, MirrorStatus.ACTIVE)


@dataclass(frozen=True, slots=True)
class Opened:
    """One try under way: its ``Attempt``, the ``Mirror`` it goes through, and that mirror's address."""

    attempt_id: uuid.UUID
    download_id: uuid.UUID
    mirror_id: uuid.UUID
    url: str
    #: The download's bytes when the try began, so its own count is the difference.
    started_size: int


class AttemptRepo(ABC):
    @abstractmethod
    async def open(self, download: Any) -> Opened | None:
        """Start a try through the first usable mirror by priority, or ``None`` when every mirror is spent.

        ``download`` is loaded with ``RELATED``. The mirror becomes ``active``.
        """
        ...

    @abstractmethod
    async def close(self, opened: Opened, status: AttemptStatus) -> None:
        """End the try: its status, when it ended, and the bytes it moved."""
        ...

    @abstractmethod
    async def spare(self, opened: Opened) -> bool:
        """Whether the download has a usable mirror besides the try's."""
        ...

    @abstractmethod
    async def retire(self, opened: Opened, status: MirrorStatus) -> None:
        """Mark the try's mirror ``failed`` or ``exhausted``."""
        ...
