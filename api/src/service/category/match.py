"""Which category a file belongs in, by its extension or its Content-Type (#174)."""

from __future__ import annotations

from collections.abc import Sequence
from pathlib import PurePosixPath

from src.data.repo.category import CategoryRow


def match_category(
    categories: Sequence[CategoryRow], filename: str | None, content_type: str | None = None
) -> CategoryRow | None:
    """The category whose rules take this file, or ``None`` for Downloads to keep it.

    An extension is the stronger sign: it is tried first, across every category,
    and only then the Content-Type. Two categories claiming the same thing go to
    the one placed first, so the answer does not depend on how the rows came back.
    """
    ordered = sorted(categories, key=lambda category: category.position)
    extension = PurePosixPath((filename or "").strip().lower()).suffix
    if extension:
        for category in ordered:
            if extension in category.extensions:
                return category
    mime = (content_type or "").split(";", 1)[0].strip().lower()
    if mime:
        for category in ordered:
            if any(mime.startswith(prefix) for prefix in category.mime_prefixes):
                return category
    return None
