from __future__ import annotations

import uuid
from collections.abc import Sequence
from typing import cast

from tortoise.transactions import in_transaction

from src.data.db.model import Category, Download
from src.data.repo.category.interface import CategoryRepo, CategoryRow
from src.data.type import DOWNLOADS_ID


def _row(category: Category) -> CategoryRow:
    return CategoryRow(category.id, category.name, category.slug, category.folder, category.position, category.builtin)


class CategoryDatabaseRepo(CategoryRepo):
    async def list_all(self) -> list[CategoryRow]:
        return [_row(category) for category in await Category.all().order_by("position", "created_at")]

    async def get(self, id: uuid.UUID) -> CategoryRow | None:
        found = await Category.filter(id=id).first()
        return _row(found) if found else None

    async def by_name(self, name: str) -> CategoryRow | None:
        wanted = name.strip().lower()
        for category in await Category.all():
            if category.name.lower() == wanted or category.slug == wanted:
                return _row(category)
        return None

    async def create(self, name: str, slug: str, folder: str) -> CategoryRow:
        last = await Category.all().order_by("-position").first()
        position = last.position + 1 if last is not None else 0
        return _row(await Category.create(name=name, slug=slug, folder=folder, position=position))

    async def update(
        self, id: uuid.UUID, *, name: str | None = None, slug: str | None = None, folder: str | None = None
    ) -> CategoryRow | None:
        category = await Category.filter(id=id).first()
        if category is None:
            return None
        changed = {key: value for key, value in (("name", name), ("slug", slug), ("folder", folder)) if value is not None}
        for key, value in changed.items():
            setattr(category, key, value)
        if changed:
            await category.save(update_fields=list(changed))
        return _row(category)

    async def reorder(self, ids: Sequence[uuid.UUID]) -> None:
        async with in_transaction() as conn:
            for position, id in enumerate(ids):
                await Category.filter(id=id).using_db(conn).update(position=position)

    async def delete(self, id: uuid.UUID) -> bool:
        async with in_transaction() as conn:
            category = await Category.filter(id=id).using_db(conn).first()
            if category is None:
                return False
            # Removed downloads still point here, and the key is RESTRICT.
            await Download.filter(category_id=id).using_db(conn).update(category_id=DOWNLOADS_ID)
            await category.delete(using_db=conn)
        return True

    async def usage(self) -> dict[uuid.UUID, int]:
        """One count per list item: a standalone download or a collection, not a collection's video."""
        held = await Download.filter(deleted_at__isnull=True, parent_id__isnull=True).values_list(
            "category_id", flat=True
        )
        counts: dict[uuid.UUID, int] = {}
        for category_id in held:
            counts[cast(uuid.UUID, category_id)] = counts.get(cast(uuid.UUID, category_id), 0) + 1
        return counts
