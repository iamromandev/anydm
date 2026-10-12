"""Which category an add lands in: the one asked for, else the one its file type says, else Downloads."""

from __future__ import annotations

import uuid

from src.data.repo.category import DOWNLOADS, CategoryRepo, CategoryRow
from src.data.type import DOWNLOADS_ID, MUSIC_SLUG
from src.service.category import error as category_error
from src.service.category.match import match_category


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


async def pick_or_match(
    repo: CategoryRepo | None,
    category_id: uuid.UUID | None,
    *,
    filename: str | None = None,
    audio_only: bool = False,
) -> CategoryRow:
    """The category an add lands in.

    A choice always wins. Without one, an audio-only download goes to Music and
    anything else to the category its filename says; nothing that matches stays in Downloads.
    """
    if category_id is not None or repo is None:
        return await pick_category(repo, category_id)
    rows = await repo.list_all()
    if audio_only:
        music = next((row for row in rows if row.slug == MUSIC_SLUG), None)
        if music is not None:
            return music
    return match_category(rows, filename) or await pick_category(repo, None)
