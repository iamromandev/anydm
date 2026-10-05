from __future__ import annotations

import uuid
from abc import abstractmethod
from collections.abc import Mapping, Sequence
from typing import Any

from tortoise.expressions import Q

from src.core.base import CrudRepo
from src.core.success import Meta
from src.data.db.model import Download
from src.data.repo.download.interface.file import FileRow
from src.data.schema.download import DownloadSummarySchema
from src.data.type import CONTAINER_KINDS, DownloadStatus

#: Not a playlist or channel container: a download with no media, or media of another kind.
NOT_CONTAINER = Q(media__id__isnull=True) | Q(media__kind__not_in=list(CONTAINER_KINDS))

#: The relations every read loads, so ``describe`` and a schema need no other query.
RELATED = ("media", "mirrors__source__url", "mirrors__source__provider", "mirrors__source__torrents")


class DownloadRepo(CrudRepo[Download]):
    @abstractmethod
    async def create_site(
        self, download: dict[str, Any], site: dict[str, Any], filename: str, mime_type: str | None
    ) -> Download:
        """A site download, its ``SiteDetail`` and its one file, in one transaction."""
        ...

    @abstractmethod
    async def create_direct(self, download: dict[str, Any], filename: str) -> Download: ...

    @abstractmethod
    async def create_torrent(self, download: dict[str, Any], files: Sequence[FileRow]) -> Download: ...

    @abstractmethod
    async def claim_next(self) -> Download | None:
        """Take the first runnable pending HTTP download and mark it ``downloading``.

        Runnable: not deleted, and ``start_at`` and ``next_attempt_at`` unset or past.
        """
        ...

    @abstractmethod
    async def recover_orphans(self) -> int:
        """Requeue every HTTP download left mid-flight by a dead process. Returns the count."""
        ...

    @abstractmethod
    async def flush_progress(self, download_id: uuid.UUID, *, downloaded_bytes: int, total_bytes: int | None) -> None:
        """Byte counts only: speed and ETA live in ``LiveStats``."""
        ...

    @abstractmethod
    async def list_items(
        self,
        page: int,
        page_size: int,
        statuses: Sequence[DownloadStatus] | None,
        sort: str,
        speeds: Mapping[uuid.UUID, int],
    ) -> tuple[list[tuple[str, uuid.UUID]], Meta]:
        """One page of ``list_item``: ``(type, id)`` in order. ``speeds`` are the live ones, for ``speed_bps``."""
        ...

    @abstractmethod
    async def summary(self) -> DownloadSummarySchema: ...

    @abstractmethod
    async def by_ids(self, ids: Sequence[uuid.UUID]) -> list[Download]:
        """These downloads with ``RELATED`` loaded, in the order asked."""
        ...

    @abstractmethod
    async def by_statuses(self, statuses: Sequence[DownloadStatus]) -> list[Download]:
        """Standalone downloads (no parent, and not themselves containers) in one of ``statuses``, oldest first."""
        ...

    @abstractmethod
    async def get_active_by_id(self, download_id: uuid.UUID) -> Download | None: ...

    @abstractmethod
    async def statuses_by_ref(self, provider: str, ref_ids: Sequence[str]) -> dict[str, DownloadStatus]:
        """Each of ``ref_ids`` that a download not removed holds, with that download's status.

        For the picker's "already have it". Canceled downloads hold nothing. A
        video held twice reports the one furthest along: complete, then queued,
        then failed.
        """
        ...

    @abstractmethod
    async def torrents_to_watch(self) -> list[Download]:
        """Every torrent row the monitor should mirror, soft-deleted ones excepted."""
        ...

    @abstractmethod
    async def by_ref(self, provider: str, ref_id: str) -> Download | None: ...
