"""Where a torrent's files live on disk. Pure, apart from looking at the disk.

Every torrent gets a folder of its own under the root (#107). Before that each
one was written flat into the root, so two torrents sharing a file name,
``poster.jpg`` or ``Subs/English.srt``, overwrote each other, and the folder a
task recorded was one rqbit never used.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from pathlib import Path

#: Path separators, characters Windows refuses, and control characters.
_UNSAFE = re.compile(r'[/\\<>:"|?*\x00-\x1f]')
_MAX_NAME = 150


def torrent_folder(root: Path, name: str, info_hash: str) -> Path:
    """The folder a new torrent is written into: its name, made safe.

    A torrent's name is written by a stranger, so it can't climb out of
    ``root`` or hide behind a leading dot. A folder already holding files
    belongs to something else, so the torrent goes beside it with its hash;
    an empty one, what a deleted torrent of the same name leaves, is reused.
    """
    stem = _UNSAFE.sub("_", name).strip(" .")[:_MAX_NAME].strip(" .") or info_hash
    folder = root / stem
    if folder.exists() and not (folder.is_dir() and not any(folder.iterdir())):
        folder = root / f"{stem} [{info_hash[:8]}]"
    return folder


def stored_folder(file_path: str | None, root: Path, paths: Iterable[str]) -> Path:
    """The folder a task's torrent really is in, given the one its row records.

    A torrent added before #107 records ``<root>/<name>`` but was written flat
    into ``root``: when the recorded folder is missing and its files are in
    the root, the root it is. A newer torrent with nothing on disk yet keeps
    the folder it recorded, which is where rqbit will write it.
    """
    if not file_path:
        return root
    folder = Path(file_path)
    if not folder.is_dir() and any((root / path).exists() for path in paths):
        return root
    return folder
