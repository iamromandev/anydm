from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence

from src.data.db.model import DownloadFile

#: ``(index, path, size_bytes, selected)``. A tuple rather than the service
#: layer's ``FileInfo``: the data layer imports nothing from ``src.lib`` or
#: ``src.service``.
FileRow = tuple[int, str, int, bool]


class FileRepo(ABC):
    @abstractmethod
    async def replace(self, download_id: uuid.UUID, files: Sequence[FileRow]) -> None:
        """Make the stored file list exactly ``files``.

        Idempotent by construction: startup reconciliation calls this with the
        same list it wrote before, and must not end up with two of everything.
        """
        ...

    @abstractmethod
    async def single(self, download_id: uuid.UUID) -> DownloadFile | None:
        """A site or direct download's one file, at index 0."""
        ...

    @abstractmethod
    async def set_single(self, download_id: uuid.UUID, *, path: str, mime_type: str | None) -> DownloadFile:
        """Create or rename the one file; planning names it."""
        ...

    @abstractmethod
    async def finish_single(self, download_id: uuid.UUID, *, path: str, size_bytes: int) -> None:
        """The finished file's name and size, counted as fully downloaded."""
        ...

    @abstractmethod
    async def get(self, download_id: uuid.UUID, index: int) -> DownloadFile | None: ...

    @abstractmethod
    async def list_for(self, download_id: uuid.UUID) -> list[DownloadFile]:
        """Every file row, in index order."""
        ...

    @abstractmethod
    async def list_for_downloads(self, ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, list[DownloadFile]]:
        """Every file of each download, in index order, in one query.

        For a page of the list: asking once per download would be a query per
        row. Every id asked for has a key, with an empty list if it has none.
        """
        ...

    @abstractmethod
    async def selected_indexes(self, download_id: uuid.UUID) -> list[int]:
        """The indexes the user chose, sorted — what the engine's selection takes."""
        ...

    @abstractmethod
    async def flush_progress(self, download_id: uuid.UUID, file_progress: Sequence[int]) -> None:
        """Write per-file byte counts, positionally by index.

        The engine's array and the stored rows can disagree for a tick after a
        selection change, so entries with no matching row are dropped rather
        than treated as an error.
        """
        ...
