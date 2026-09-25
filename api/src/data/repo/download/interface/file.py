from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence

from src.data.db.model import File

#: ``(index, path, size_bytes, selected)``. A tuple rather than the service
#: layer's ``FileInfo`` for the same reason ``SegmentRepo`` takes triples: the
#: data layer imports nothing from ``src.lib`` or ``src.service``.
FileRow = tuple[int, str, int, bool]


class FileRepo(ABC):
    @abstractmethod
    async def replace(self, task_id: uuid.UUID, files: Sequence[FileRow]) -> None:
        """Make the stored file list exactly ``files``.

        Idempotent by construction: startup reconciliation calls this with the
        same list it wrote before, and must not end up with two of everything.
        """
        ...

    @abstractmethod
    async def list_for(self, task_id: uuid.UUID) -> list[File]:
        """Every file row, in torrent order."""
        ...

    @abstractmethod
    async def list_for_tasks(self, task_ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, list[File]]:
        """Every file row of each task, in torrent order, in one query.

        For a page of the list: asking once per task would be a query per row.
        Every task asked for has a key, with an empty list if it has no rows.
        """
        ...

    @abstractmethod
    async def selected_indexes(self, task_id: uuid.UUID) -> list[int]:
        """The indexes the user chose, sorted — what the engine's selection takes."""
        ...

    @abstractmethod
    async def flush_progress(self, task_id: uuid.UUID, file_progress: Sequence[int]) -> None:
        """Write per-file byte counts, positionally by index.

        The engine's array and the stored rows can disagree for a tick after a
        selection change, so entries with no matching row are dropped rather
        than treated as an error.
        """
        ...

    @abstractmethod
    async def selected_size(self, task_id: uuid.UUID) -> int:
        """Total bytes of the selection — the task's ``total_bytes``."""
        ...
