"""Give every built-in folder and the Main queue a row where it has none.

Run at startup, the way search sources are seeded. Never overwrites a row a
person has edited: only missing names are inserted.
"""

from __future__ import annotations

from src.data.repo.download.interface.folder import FolderRepo, FolderRow
from src.data.repo.download.interface.queue import QueueRepo, QueueRow
from src.data.type import MAIN_QUEUE, OTHER_FOLDER

FOLDERS: tuple[FolderRow, ...] = (
    FolderRow("Video", "video", "Video", ("mp4", "mkv", "webm", "avi", "mov", "m4v"), 0),
    FolderRow("Music", "music", "Music", ("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac"), 1),
    FolderRow("Software", "software", "Software", ("exe", "msi", "dmg", "pkg", "deb", "rpm", "apk", "appimage"), 2),
    FolderRow(
        "Documents",
        "documents",
        "Documents",
        ("pdf", "epub", "mobi", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt"),
        3,
    ),
    FolderRow("Compressed", "compressed", "Compressed", ("zip", "rar", "7z", "tar", "gz", "xz", "bz2", "iso"), 4),
    FolderRow(OTHER_FOLDER, "other", OTHER_FOLDER, (), 5),
)


async def seed_organization(folders: FolderRepo, queues: QueueRepo, *, workers: int) -> tuple[int, int]:
    """Insert the missing folders and the Main queue. Returns how many of each were added."""
    added_folders = await folders.insert_missing(FOLDERS)
    added_queues = await queues.insert_missing([QueueRow(MAIN_QUEUE, max(1, workers), 0)])
    return added_folders, added_queues
