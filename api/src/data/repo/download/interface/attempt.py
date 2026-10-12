from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence
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
    async def close(
        self, opened: Opened, status: AttemptStatus, *, error: str | None = None, error_code: str | None = None
    ) -> None:
        """End the try: its status, when it ended, the bytes it moved, and why it failed when it did."""
        ...

    @abstractmethod
    async def record_failure(self, download_id: uuid.UUID, error: str | None, error_code: str | None) -> None:
        """A failed try recorded after the fact, on the download's primary mirror.

        For a failure no worker's try carries: a torrent the engine reports failed, or a download with
        no usable mirror left. Nothing is recorded for a download that has no mirror.
        """
        ...

    @abstractmethod
    async def latest_errors(self, download_ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, tuple[str | None, str | None]]:
        """``(error, error_code)`` of each download's newest try, by download id; a download with no try is left out."""
        ...

    @abstractmethod
    async def spare(self, opened: Opened) -> bool:
        """Whether the download has a usable mirror besides the try's."""
        ...

    @abstractmethod
    async def retire(self, opened: Opened, status: MirrorStatus) -> None:
        """Mark the try's mirror ``failed`` or ``exhausted``."""
        ...
