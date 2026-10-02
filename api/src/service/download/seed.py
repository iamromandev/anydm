"""Give every built-in category and the Main queue a row where it has none.

Run at startup, the way search sources are seeded. Never overwrites a row a
person has edited: only missing names are inserted.
"""

from __future__ import annotations

from src.data.repo.download.interface.category import CategoryRepo, CategoryRow
from src.data.repo.download.interface.queue import QueueRepo, QueueRow
from src.data.type import MAIN_QUEUE, OTHER_CATEGORY

CATEGORIES: tuple[CategoryRow, ...] = (
    CategoryRow("Video", "Video", ("mp4", "mkv", "webm", "avi", "mov", "m4v"), 0),
    CategoryRow("Music", "Music", ("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac"), 1),
    CategoryRow("Software", "Software", ("exe", "msi", "dmg", "pkg", "deb", "rpm", "apk", "appimage"), 2),
    CategoryRow(
        "Documents", "Documents", ("pdf", "epub", "mobi", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt"), 3
    ),
    CategoryRow("Compressed", "Compressed", ("zip", "rar", "7z", "tar", "gz", "xz", "bz2", "iso"), 4),
    CategoryRow(OTHER_CATEGORY, OTHER_CATEGORY, (), 5),
)


async def seed_organization(categories: CategoryRepo, queues: QueueRepo, *, workers: int) -> tuple[int, int]:
    """Insert the missing categories and the Main queue. Returns how many of each were added."""
    added_categories = await categories.insert_missing(CATEGORIES)
    added_queues = await queues.insert_missing([QueueRow(MAIN_QUEUE, max(1, workers), 0)])
    return added_categories, added_queues
