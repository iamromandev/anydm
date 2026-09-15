from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence

from src.data.db.model import TorrentFile

#: ``(index, path, size_bytes, selected)``. A tuple rather than the service
#: layer's ``TorrentFileInfo`` for the same reason ``SegmentRepo`` takes
#: triples: the data layer imports nothing from ``src.lib`` or ``src.service``.
FileRow = tuple[int, str, int, bool]


class TorrentFileRepo(ABC):
    @abstractmethod
    async def replace(self, task_id: uuid.UUID, files: Sequence[FileRow]) -> None:
        """Make the stored file list exactly ``files``.

        Idempotent by construction: startup reconciliation calls this with the
        same list it wrote before, and must not end up with two of everything.
        """
        ...

    @abstractmethod
    async def list_for(self, task_id: uuid.UUID) -> list[TorrentFile]:
        """Every file row, in torrent order."""
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
