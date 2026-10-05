from __future__ import annotations

import uuid
from collections.abc import Sequence

from src.data.db.model import DownloadFile
from src.data.repo.download.interface.file import FileRepo, FileRow
from src.data.repo.download.mime import mime_of


class FileDatabaseRepo(FileRepo):
    async def replace(self, download_id: uuid.UUID, files: Sequence[FileRow]) -> None:
        # Delete then insert rather than upsert: the list is small, it changes
        # only when a torrent is added or re-added, and this cannot leave a
        # stale row behind for a file the torrent no longer has.
        await DownloadFile.filter(download_id=download_id).delete()
        if files:
            await DownloadFile.bulk_create(
                [
                    DownloadFile(
                        download_id=download_id,
                        index=index,
                        path=path,
                        size_bytes=size,
                        selected=selected,
                        mime_type=mime_of(path),
                    )
                    for index, path, size, selected in files
                ]
            )

    async def single(self, download_id: uuid.UUID) -> DownloadFile | None:
        return await DownloadFile.filter(download_id=download_id, index=0).first()

    async def set_single(self, download_id: uuid.UUID, *, path: str, mime_type: str | None) -> DownloadFile:
        row, _ = await DownloadFile.update_or_create(
            defaults={"path": path, "mime_type": mime_type}, download_id=download_id, index=0
        )
        return row

    async def finish_single(self, download_id: uuid.UUID, *, path: str, size_bytes: int) -> None:
        await DownloadFile.filter(download_id=download_id, index=0).update(
            path=path, size_bytes=size_bytes, downloaded_bytes=size_bytes
        )

    async def get(self, download_id: uuid.UUID, index: int) -> DownloadFile | None:
        return await DownloadFile.filter(download_id=download_id, index=index).first()

    async def list_for(self, download_id: uuid.UUID) -> list[DownloadFile]:
        return await DownloadFile.filter(download_id=download_id).order_by("index")

    async def list_for_downloads(self, ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, list[DownloadFile]]:
        by_download: dict[uuid.UUID, list[DownloadFile]] = {download_id: [] for download_id in ids}
        if by_download:
            for row in await DownloadFile.filter(download_id__in=list(by_download)).order_by("index"):
                by_download[row.download_id].append(row)
        return by_download

    async def selected_indexes(self, download_id: uuid.UUID) -> list[int]:
        rows = await DownloadFile.filter(download_id=download_id, selected=True).order_by("index")
        return [row.index for row in rows]

    async def flush_progress(self, download_id: uuid.UUID, file_progress: Sequence[int]) -> None:
        if not file_progress:
            return
        changed: list[DownloadFile] = []
        for row in await DownloadFile.filter(download_id=download_id).order_by("index"):
            if row.index >= len(file_progress):
                continue
            value = int(file_progress[row.index])
            if row.downloaded_bytes != value:
                row.downloaded_bytes = value
                changed.append(row)
        if changed:
            await DownloadFile.bulk_update(changed, fields=["downloaded_bytes"])
