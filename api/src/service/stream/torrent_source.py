"""Which file, out of a torrent, gets streamed.

Ported from ``ui/apps/web/src/component/features/hero-input/kind.ts``'s
media-extension list. Kept in sync manually — both lists are short and
change rarely.
"""

from __future__ import annotations

from src.lib.torrent.protocol import FileInfo

MEDIA_EXTENSIONS = (
    ".mp4", ".mkv", ".webm", ".avi", ".mov", ".wmv", ".flv", ".m4v",
    ".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg",
)


def pick_media_file(files: list[FileInfo]) -> FileInfo | None:
    candidates = [f for f in files if f.path.lower().endswith(MEDIA_EXTENSIONS)]
    if not candidates:
        return None
    return max(candidates, key=lambda f: f.size_bytes)
