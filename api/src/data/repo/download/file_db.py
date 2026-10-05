from __future__ import annotations

import uuid
from collections.abc import Sequence
from pathlib import PurePosixPath

from src.data.db.model import File
from src.data.repo.download.interface.file import FileRepo, FileRow
from src.data.repo.download.mime import mime_of


def _name(path: str) -> str:
    """The file's own name: the last segment of its path inside the download's folder."""
    return PurePosixPath(path).name


class FileDatabaseRepo(FileRepo):
    async def replace(self, download_id: uuid.UUID, files: Sequence[FileRow]) -> None:
        # Delete then insert rather than upsert: the list is small, it changes
        # only when a torrent is added or re-added, and this cannot leave a
        # stale row behind for a file the torrent no longer has.
        await File.filter(download_id=download_id).delete()
        if files:
            await File.bulk_create(
                [
                    File(
                        download_id=download_id,
                        index=index,
                        path=path,
                        filename=_name(path),
                        size=size,
                        selected=selected,
                        mime_type=mime_of(path),
                    )
                    for index, path, size, selected in files
                ]
            )

    async def single(self, download_id: uuid.UUID) -> File | None:
        return await File.filter(download_id=download_id, index=0).first()

    async def set_single(self, download_id: uuid.UUID, *, path: str, mime_type: str | None) -> File:
        row, _ = await File.update_or_create(
            defaults={"path": path, "filename": _name(path), "mime_type": mime_type},
            download_id=download_id,
            index=0,
        )
        return row

    async def finish_single(self, download_id: uuid.UUID, *, path: str, size_bytes: int) -> None:
        await File.filter(download_id=download_id, index=0).update(
            path=path, filename=_name(path), size=size_bytes, downloaded_bytes=size_bytes
        )

    async def get(self, download_id: uuid.UUID, index: int) -> File | None:
        return await File.filter(download_id=download_id, index=index).first()

    async def list_for(self, download_id: uuid.UUID) -> list[File]:
        return await File.filter(download_id=download_id).order_by("index")

    async def list_for_downloads(self, ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, list[File]]:
        by_download: dict[uuid.UUID, list[File]] = {download_id: [] for download_id in ids}
        if by_download:
            for row in await File.filter(download_id__in=list(by_download)).order_by("index"):
                by_download[row.download_id].append(row)
        return by_download

    async def selected_indexes(self, download_id: uuid.UUID) -> list[int]:
        rows = await File.filter(download_id=download_id, selected=True).order_by("index")
        return [row.index for row in rows]

    async def flush_progress(self, download_id: uuid.UUID, file_progress: Sequence[int]) -> None:
        if not file_progress:
            return
        changed: list[File] = []
        for row in await File.filter(download_id=download_id).order_by("index"):
            if row.index >= len(file_progress):
                continue
            value = int(file_progress[row.index])
            if row.downloaded_bytes != value:
                row.downloaded_bytes = value
                changed.append(row)
        if changed:
            await File.bulk_update(changed, fields=["downloaded_bytes"])
