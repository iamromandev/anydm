from __future__ import annotations

import uuid
from collections.abc import Sequence

from src.data.db.model import File
from src.data.repo.download.interface.file import FileRepo, FileRow


class FileDatabaseRepo(FileRepo):
    async def replace(self, task_id: uuid.UUID, files: Sequence[FileRow]) -> None:
        # Delete then insert rather than upsert: the list is small, it changes
        # only when a torrent is added or re-added, and this cannot leave a
        # stale row behind for a file the torrent no longer has.
        await File.filter(task_id=task_id).delete()
        if not files:
            return
        await File.bulk_create(
            [
                File(
                    task_id=task_id,
                    index=index,
                    path=path,
                    size_bytes=size_bytes,
                    selected=selected,
                )
                for index, path, size_bytes, selected in files
            ]
        )

    async def list_for(self, task_id: uuid.UUID) -> list[File]:
        return await File.filter(task_id=task_id).order_by("index")

    async def selected_indexes(self, task_id: uuid.UUID) -> list[int]:
        rows = await File.filter(task_id=task_id, selected=True).order_by("index")
        return [row.index for row in rows]

    async def flush_progress(self, task_id: uuid.UUID, file_progress: Sequence[int]) -> None:
        if not file_progress:
            return
        rows = await File.filter(task_id=task_id).order_by("index")
        changed: list[File] = []
        for row in rows:
            if row.index >= len(file_progress):
                continue
            value = int(file_progress[row.index])
            if row.downloaded_bytes != value:
                row.downloaded_bytes = value
                changed.append(row)
        if changed:
            await File.bulk_update(changed, fields=["downloaded_bytes"])

    async def selected_size(self, task_id: uuid.UUID) -> int:
        rows = await File.filter(task_id=task_id, selected=True)
        return sum(row.size_bytes for row in rows)
