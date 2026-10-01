from __future__ import annotations

import uuid
from collections.abc import Sequence

from src.data.db.model import Category
from src.data.repo.download.interface.category import CategoryRepo, CategoryRow
from src.data.type import OTHER_CATEGORY


class CategoryDatabaseRepo(CategoryRepo):
    async def list_all(self) -> list[Category]:
        return await Category.all().order_by("position", "name")

    async def get(self, category_id: uuid.UUID) -> Category | None:
        return await Category.filter(id=category_id).first()

    async def other(self) -> Category:
        return await Category.get(name=OTHER_CATEGORY)

    async def insert_missing(self, rows: Sequence[CategoryRow]) -> int:
        have = set(await Category.all().values_list("name", flat=True))
        fresh = [
            Category(name=row.name, save_dir=row.save_dir, extensions=list(row.extensions), position=row.position)
            for row in rows
            if row.name not in have
        ]
        if fresh:
            await Category.bulk_create(fresh)
        return len(fresh)
