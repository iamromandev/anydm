"""Where a torrent's files live on disk. Pure, apart from looking at the disk.

Every torrent gets a folder of its own under the root (#107). Before that each
one was written flat into the root, so two torrents sharing a file name,
``poster.jpg`` or ``Subs/English.srt``, overwrote each other.
"""

from __future__ import annotations

from pathlib import Path

from src.lib.folder import named_folder


def torrent_folder(root: Path, name: str, info_hash: str) -> Path:
    """The folder a new torrent is written into: its name, made safe.

    A torrent's name is written by a stranger, so it can't climb out of
    ``root`` or hide behind a leading dot. A folder already holding files
    belongs to something else, so the torrent goes beside it with its hash;
    an empty one, what a deleted torrent of the same name leaves, is reused.
    The rules are ``named_folder``'s, which a playlist's folder shares (v0.5).
    """
    return named_folder(root, name, info_hash)
