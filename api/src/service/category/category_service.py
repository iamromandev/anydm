"""Categories as one list: add, rename, repoint, reorder and delete."""

from __future__ import annotations

import uuid
from collections.abc import Sequence
from pathlib import Path

from src.data.repo.category import CategoryRepo, CategoryRow
from src.data.schema.transfer import CategoryListSchema, CategorySchema
from src.data.type import slugify
from src.service.category import error as category_error
from src.service.download.folders import inside


def _view(row: CategoryRow, count: int = 0) -> CategorySchema:
    return CategorySchema(
        id=row.id,
        name=row.name,
        slug=row.slug,
        folder=row.folder,
        position=row.position,
        builtin=row.builtin,
        count=count,
    )


class CategoryService:
    def __init__(self, repo: CategoryRepo, downloads_root: Path) -> None:
        self._repo = repo
        self._root = downloads_root

    async def list(self) -> CategoryListSchema:
        counts = await self._repo.usage()
        return CategoryListSchema(
            categories=[_view(row, counts.get(row.id, 0)) for row in await self._repo.list_all()]
        )

    async def create(self, name: str, folder: str) -> CategorySchema:
        clean, slug = await self._named(name, None)
        return _view(await self._repo.create(clean, slug, self._folder(folder)))

    async def update(self, id: uuid.UUID, name: str | None, folder: str | None) -> CategorySchema:
        row = await self._require(id)
        if folder is not None and row.builtin and self._folder(folder) != row.folder:
            raise category_error.builtin()
        clean, slug = await self._named(name, row) if name is not None else (None, None)
        updated = await self._repo.update(
            id, name=clean, slug=slug, folder=self._folder(folder) if folder is not None else None
        )
        assert updated is not None  # it existed a moment ago
        return _view(updated, (await self._repo.usage()).get(id, 0))

    async def order(self, ids: Sequence[uuid.UUID]) -> CategoryListSchema:
        known = {row.id for row in await self._repo.list_all()}
        if len(ids) != len(known) or set(ids) != known:
            raise category_error.bad_order()
        await self._repo.reorder(ids)
        return await self.list()

    async def delete(self, id: uuid.UUID) -> None:
        row = await self._require(id)
        if row.builtin:
            raise category_error.builtin()
        count = (await self._repo.usage()).get(id, 0)
        if count:
            raise category_error.in_use(row.name, count)
        await self._repo.delete(id)

    async def _require(self, id: uuid.UUID) -> CategoryRow:
        row = await self._repo.get(id)
        if row is None:
            raise category_error.not_found(id)
        return row

    async def _named(self, name: str, current: CategoryRow | None) -> tuple[str, str]:
        clean = name.strip()
        if not clean:
            raise category_error.name_missing()
        slug = slugify(clean)
        if not slug:
            raise category_error.name_meaningless()
        for other in (await self._repo.by_name(clean), await self._repo.by_name(slug)):
            if other is not None and (current is None or other.id != current.id):
                raise category_error.name_taken(clean)
        return clean, slug

    def _folder(self, folder: str) -> str:
        """``folder`` checked against the download root, and written one way: ``a/b``, or ``""`` for the root."""
        base = self._root.resolve()
        relative = inside(self._root, folder.strip()).relative_to(base).as_posix()
        return "" if relative == "." else relative
