"""Which category an add lands in: the one asked for, else Downloads."""

from __future__ import annotations

import uuid

from src.data.repo.category import DOWNLOADS, CategoryRepo, CategoryRow
from src.data.type import DOWNLOADS_ID
from src.service.category import error as category_error


async def pick_category(repo: CategoryRepo | None, category_id: uuid.UUID | None) -> CategoryRow:
    """``None`` is Downloads. Without a repository (tests that don't care) only Downloads exists."""
    wanted = category_id or DOWNLOADS_ID
    if repo is None:
        if wanted == DOWNLOADS_ID:
            return DOWNLOADS
        raise category_error.unknown(wanted)
    row = await repo.get(wanted)
    if row is None:
        raise category_error.unknown(wanted)
    return row
