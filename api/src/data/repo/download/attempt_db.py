from __future__ import annotations

from typing import Any

from src.core.common import now
from src.data.db.model import Attempt, Download, Mirror
from src.data.repo.download.interface.attempt import USABLE_MIRRORS, AttemptRepo, Opened
from src.data.type import AttemptStatus, MirrorStatus


class AttemptDatabaseRepo(AttemptRepo):
    async def open(self, download: Any) -> Opened | None:
        usable = sorted(
            (mirror for mirror in download.mirrors if mirror.status in USABLE_MIRRORS),
            key=lambda mirror: (mirror.priority, mirror.created_at),
        )
        if not usable:
            return None
        mirror = usable[0]
        if mirror.status != MirrorStatus.ACTIVE:
            mirror.status = MirrorStatus.ACTIVE
            await Mirror.filter(id=mirror.id).update(status=MirrorStatus.ACTIVE)
        attempt = await Attempt.create(mirror_id=mirror.id, status=AttemptStatus.RUNNING)
        return Opened(attempt.id, download.id, mirror.id, mirror.source.url.value, download.downloaded_size)

    async def close(self, opened: Opened, status: AttemptStatus) -> None:
        download = await Download.get_or_none(id=opened.download_id)
        size = download.downloaded_size if download is not None else opened.started_size
        await Attempt.filter(id=opened.attempt_id).update(
            status=status, completed_at=now(), downloaded_bytes=max(0, size - opened.started_size)
        )

    async def retire(self, opened: Opened, status: MirrorStatus) -> bool:
        await Mirror.filter(id=opened.mirror_id).update(status=status)
        return (
            await Mirror.filter(download_id=opened.download_id, status__in=list(USABLE_MIRRORS))
            .exclude(id=opened.mirror_id)
            .exists()
        )
