"""The categories every install starts with, and how a category's name becomes its slug."""

from __future__ import annotations

import re
import uuid

#: Downloads: the default category, the root of ``DOWNLOAD_DIR``. Fixed, so code
#: and the migration name it without a lookup.
DOWNLOADS_ID = uuid.UUID("00000000-0000-4000-8000-000000000001")

#: ``(name, slug, folder)`` in position order: one per value of the old ``Folder`` enum.
SEEDED_CATEGORIES: tuple[tuple[str, str, str], ...] = (
    ("Downloads", "downloads", ""),
    ("Videos", "videos", "videos"),
    ("Movies", "movies", "movies"),
    ("TV shows", "tv_shows", "tv_shows"),
    ("Music", "music", "music"),
    ("Audiobooks", "audiobooks", "audiobooks"),
    ("Podcasts", "podcasts", "podcasts"),
    ("Documents", "documents", "documents"),
    ("Ebooks", "ebooks", "ebooks"),
    ("Images", "images", "images"),
    ("Photos", "photos", "photos"),
    ("Software", "software", "software"),
    ("Games", "games", "games"),
    ("Archives", "archives", "archives"),
    ("Other", "other", "other"),
)

_NOT_WORD = re.compile(r"[^\w]+")
_RUNS = re.compile(r"_+")


def slugify(name: str) -> str:
    """Lower case, one ``_`` between runs of letters and digits, none at either end. ``""`` when nothing is left."""
    return _RUNS.sub("_", _NOT_WORD.sub("_", name.strip().lower())).strip("_")
