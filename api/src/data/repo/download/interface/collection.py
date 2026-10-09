from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

from src.core.success import Meta
from src.data.db.model import Download
from src.data.type import DownloadStatus

#: ``(download id, status, downloaded_size, total_size)`` of one member.
MemberRow = tuple[uuid.UUID, DownloadStatus, int, int | None]


@dataclass(frozen=True, slots=True)
class EntryRow:
    """One video of a collection, unplanned: its formats and name are chosen when it starts."""

    #: Its page, as the listing gives it: the address it is added, and found again, by.
    url: str
    #: Its ``Download`` fields.
    download: dict[str, Any]
    #: Its ``Media`` fields: title, kind, preset and its place in the listing.
    media: dict[str, Any]
    filename: str


class CollectionRepo(ABC):
    @abstractmethod
    async def find(self, url: str) -> Download | None:
        """The collection not removed that was added from the listing at ``url``, if any."""
        ...

    @abstractmethod
    async def get_active_by_id(self, collection_id: uuid.UUID) -> Download | None: ...

    @abstractmethod
    async def by_ids(self, ids: Sequence[uuid.UUID]) -> list[Download]:
        """In the order asked."""
        ...

    @abstractmethod
    async def create_with_entries(
        self,
        *,
        url: str,
        provider: str,
        collection: dict[str, Any],
        media: dict[str, Any],
        entries: Sequence[EntryRow],
    ) -> Download:
        """The container added from the listing at ``url`` and its videos, in one transaction.

        ``collection`` is the container's ``Download`` fields and ``media`` its
        ``Media`` (kind, title, preset); each video is one ``EntryRow``, all of
        ``provider``.
        """
        ...

    @abstractmethod
    async def add_entries(self, collection: Download, provider: str, entries: Sequence[EntryRow]) -> None:
        """More videos of ``provider`` under an existing collection."""
        ...

    @abstractmethod
    async def downloads_page(self, collection_id: uuid.UUID, page: int, page_size: int) -> tuple[list[Download], Meta]:
        """One page of its videos not removed, in listing order."""
        ...

    @abstractmethod
    async def member_rows(self, collection_id: uuid.UUID) -> list[MemberRow]: ...

    @abstractmethod
    async def held(self, collection_id: uuid.UUID) -> dict[str, tuple[uuid.UUID, DownloadStatus]]:
        """Members by site video id: download id and status."""
        ...

    @abstractmethod
    async def pause(self, collection_id: uuid.UUID) -> list[uuid.UUID]:
        """Pause its queued and downloading videos; the ids of those that were downloading."""
        ...

    @abstractmethod
    async def resume(self, collection_id: uuid.UUID) -> int:
        """Requeue its paused and failed videos, their attempts reset. Returns how many."""
        ...

    @abstractmethod
    async def remove(self, collection_id: uuid.UUID) -> list[uuid.UUID]:
        """Cancel and soft-delete every member; their ids."""
        ...

    @abstractmethod
    async def pause_all(self) -> tuple[list[uuid.UUID], set[uuid.UUID]]:
        """Pause every collection's queued and downloading videos, in one update.

        Returns the ids of those that were downloading, and the collections touched.
        """
        ...

    @abstractmethod
    async def resume_all(self) -> set[uuid.UUID]:
        """Requeue every collection's paused and failed videos; the collections touched."""
        ...

    @abstractmethod
    async def requeue(self, ids: Sequence[uuid.UUID]) -> int:
        """Put the paused and failed among these videos back in the queue."""
        ...

    @abstractmethod
    async def watched_counts(self, ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, int]:
        """Members watched to the end, per collection. Every id asked for has a key."""
        ...

    @abstractmethod
    async def move_rows(self, collection_id: uuid.UUID, category_id: uuid.UUID, folder: str) -> None:
        """The collection's new category and folder; its live videos take the category, and the ones with a place take the folder."""
        ...

    @abstractmethod
    async def soft_delete(self, collection: Download) -> None: ...
