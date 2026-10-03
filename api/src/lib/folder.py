"""A folder named for what it holds: a torrent (#107) or a playlist (v0.5).

Pure, apart from looking at the disk.
"""

from __future__ import annotations

import re
from pathlib import Path

#: Path separators, characters Windows refuses, and control characters.
_UNSAFE = re.compile(r'[/\\<>:"|?*\x00-\x1f]')
_MAX_NAME = 150


def collection_dirname(title: str, ref_id: str) -> str:
    """A collection's directory name: its title and the site's id for it, ``Talks [PL1abc]``.

    Pure, unlike ``named_folder``: the same row always names the same directory,
    so nothing has to remember it. The id keeps two playlists with one title apart.
    """
    stem = _UNSAFE.sub("_", title).strip(" .")[:_MAX_NAME].strip(" .")
    tag = _UNSAFE.sub("_", ref_id).strip(" .")[:64]
    return f"{stem} [{tag}]" if stem else tag


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
