"""A folder named for what it holds: a torrent (#107) or a playlist (v0.5).

Pure, apart from looking at the disk.
"""

from __future__ import annotations

import re
from pathlib import Path

#: Path separators, characters Windows refuses, and control characters.
_UNSAFE = re.compile(r'[/\\<>:"|?*\x00-\x1f]')
_MAX_NAME = 150


def named_folder(root: Path, name: str, fallback: str) -> Path:
    """``root/<name>``, made safe, or ``fallback`` for a name with nothing left.

    A folder already holding files belongs to something else, so this one goes
    beside it with ``fallback``'s first eight characters. An empty one is reused.
    """
    stem = _UNSAFE.sub("_", name).strip(" .")[:_MAX_NAME].strip(" .") or fallback
    folder = root / stem
    if folder.exists() and not (folder.is_dir() and not any(folder.iterdir())):
        folder = root / f"{stem} [{fallback[:8]}]"
    return folder
