from __future__ import annotations

import uuid
from collections.abc import Sequence

from src.data.db.model import Folder
from src.data.repo.download.interface.folder import FolderRepo, FolderRow
from src.data.type import OTHER_FOLDER


class FolderDatabaseRepo(FolderRepo):
    async def list_all(self) -> list[Folder]:
        return await Folder.all().order_by("position", "name")

    async def get(self, folder_id: uuid.UUID) -> Folder | None:
        return await Folder.filter(id=folder_id).first()

    async def other(self) -> Folder:
        return await Folder.get(name=OTHER_FOLDER)

    async def insert_missing(self, rows: Sequence[FolderRow]) -> int:
        have = set(await Folder.all().values_list("name", flat=True))
        fresh = [
            Folder(
                name=row.name,
                slug=row.slug,
                save_dir=row.save_dir,
                extensions=list(row.extensions),
                position=row.position,
            )
            for row in rows
            if row.name not in have
        ]
        if fresh:
            await Folder.bulk_create(fresh)
        return len(fresh)
