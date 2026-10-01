from __future__ import annotations

import uuid
from collections.abc import Sequence

from src.data.db.model import Mirror
from src.data.repo.download.interface.mirror import MirrorRepo


class MirrorDatabaseRepo(MirrorRepo):
    async def list_for_downloads(self, ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, list[Mirror]]:
        by_download: dict[uuid.UUID, list[Mirror]] = {download_id: [] for download_id in ids}
        if by_download:
            for row in await Mirror.filter(download_id__in=list(by_download)).order_by("position"):
                by_download[row.download_id].append(row)
        return by_download
