from __future__ import annotations

import uuid
from collections.abc import Sequence
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

    async def close(
        self, opened: Opened, status: AttemptStatus, *, error: str | None = None, error_code: str | None = None
    ) -> None:
        download = await Download.get_or_none(id=opened.download_id)
        size = download.downloaded_size if download is not None else opened.started_size
        await Attempt.filter(id=opened.attempt_id).update(
            status=status,
            completed_at=now(),
            downloaded_size=max(0, size - opened.started_size),
            error=error,
            error_code=error_code,
        )

    async def record_failure(self, download_id: uuid.UUID, error: str | None, error_code: str | None) -> None:
        mirror = await Mirror.filter(download_id=download_id).order_by("priority", "created_at").first()
        if mirror is None:
            return
        await Attempt.create(
            mirror_id=mirror.id,
            status=AttemptStatus.FAILED,
            completed_at=now(),
            error=error,
            error_code=error_code,
        )

    async def latest_errors(self, download_ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, tuple[str | None, str | None]]:
        if not download_ids:
            return {}
        rows = (
            await Attempt.filter(mirror__download_id__in=list(download_ids))
            .order_by("-started_at", "-created_at")
            .values_list("mirror__download_id", "error", "error_code")
        )
        latest: dict[uuid.UUID, tuple[str | None, str | None]] = {}
        for download_id, error, error_code in rows:
            latest.setdefault(download_id, (error, error_code))
        return latest

    async def spare(self, opened: Opened) -> bool:
        return (
            await Mirror.filter(download_id=opened.download_id, status__in=list(USABLE_MIRRORS))
            .exclude(id=opened.mirror_id)
            .exists()
        )

    async def retire(self, opened: Opened, status: MirrorStatus) -> None:
        await Mirror.filter(id=opened.mirror_id).update(status=status)
