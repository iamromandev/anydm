"""An in-memory CategoryRepo seeded as the migration seeds the real one."""

import uuid
from collections.abc import Sequence
from dataclasses import replace

from src.data.repo.category import CategoryRepo, CategoryRow
from src.data.type import DOWNLOADS_ID, SEEDED_CATEGORIES


class FakeCategoryRepo(CategoryRepo):
    def __init__(self) -> None:
        self.rows: dict[uuid.UUID, CategoryRow] = {}
        for position, (name, slug, folder) in enumerate(SEEDED_CATEGORIES):
            key = DOWNLOADS_ID if slug == "downloads" else uuid.uuid4()
            self.rows[key] = CategoryRow(key, name, slug, folder, position, slug == "downloads")
        #: What ``usage`` answers; tests set it.
        self.counts: dict[uuid.UUID, int] = {}
        self.deleted: list[uuid.UUID] = []

    def named(self, slug: str) -> CategoryRow:
        return next(row for row in self.rows.values() if row.slug == slug)

    async def list_all(self) -> list[CategoryRow]:
        return sorted(self.rows.values(), key=lambda row: row.position)

    async def get(self, id: uuid.UUID) -> CategoryRow | None:
        return self.rows.get(id)

    async def by_name(self, name: str) -> CategoryRow | None:
        wanted = name.strip().lower()
        return next((r for r in self.rows.values() if r.name.lower() == wanted or r.slug == wanted), None)

    async def create(self, name: str, slug: str, folder: str) -> CategoryRow:
        row = CategoryRow(uuid.uuid4(), name, slug, folder, len(self.rows), False)
        self.rows[row.id] = row
        return row

    async def update(
        self, id: uuid.UUID, *, name: str | None = None, slug: str | None = None, folder: str | None = None
    ) -> CategoryRow | None:
        row = self.rows.get(id)
        if row is None:
            return None
        row = replace(
            row,
            name=name if name is not None else row.name,
            slug=slug if slug is not None else row.slug,
            folder=folder if folder is not None else row.folder,
        )
        self.rows[id] = row
        return row

    async def reorder(self, ids: Sequence[uuid.UUID]) -> None:
        for position, id in enumerate(ids):
            self.rows[id] = replace(self.rows[id], position=position)

    async def delete(self, id: uuid.UUID) -> bool:
        self.deleted.append(id)
        return self.rows.pop(id, None) is not None

    async def usage(self) -> dict[uuid.UUID, int]:
        return dict(self.counts)
