from __future__ import annotations

import uuid

from pydantic import model_validator

from src.core.base import BaseSchema


class CategoryRefSchema(BaseSchema):
    """A download's or a collection's category, as its row shows it."""

    id: uuid.UUID
    name: str


class CategorySchema(BaseSchema):
    id: uuid.UUID
    name: str
    slug: str
    #: Relative to DOWNLOAD_DIR; "" is its root.
    folder: str
    position: int
    #: True for Downloads alone: its folder is fixed and it can't be deleted.
    builtin: bool
    #: List items (standalone downloads and collections) in it.
    count: int = 0


class CategoryListSchema(BaseSchema):
    categories: list[CategorySchema]


class CategoryCreate(BaseSchema):
    name: str
    folder: str = ""


class CategoryPatch(BaseSchema):
    name: str | None = None
    folder: str | None = None

    @model_validator(mode="after")
    def _something_to_change(self) -> CategoryPatch:
        if self.name is None and self.folder is None:
            raise ValueError("Give name, folder, or both")
        return self


class CategoryOrderRequest(BaseSchema):
    ids: list[uuid.UUID]


class CategoryMoveRequest(BaseSchema):
    category_id: uuid.UUID
