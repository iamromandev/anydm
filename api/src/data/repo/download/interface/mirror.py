from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Sequence

from src.data.db.model import Mirror


class MirrorRepo(ABC):
    @abstractmethod
    async def list_for_downloads(self, ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, list[Mirror]]:
        """Each download's mirrors in order, in one query. Every id asked for has a key."""
        ...
