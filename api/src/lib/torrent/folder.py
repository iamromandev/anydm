"""Where a torrent's files live on disk. Pure.

Every torrent gets a folder of its own under the root (#107): its name plus
its info hash, so two torrents sharing a file name never overwrite each
other. Pure like a collection's directory: the same torrent always names the
same folder, so the folder is derived on read rather than stored on the row.
"""

from __future__ import annotations

import re
from pathlib import Path

#: Path separators, characters Windows refuses, and control characters.
_UNSAFE = re.compile(r'[/\\<>:"|?*\x00-\x1f]')
_MAX_NAME = 150


def torrent_folder(root: Path, name: str, info_hash: str) -> Path:
    """The folder a torrent is written into: its name, made safe, plus its hash.

    A torrent's name is written by a stranger, so it can't climb out of
    ``root`` or hide behind a leading dot. The hash keeps two releases with
    one name apart, and makes the folder a pure function of the row.
    """
    stem = _UNSAFE.sub("_", name).strip(" .")
    tag = _UNSAFE.sub("_", info_hash).strip(" .")
    if not stem:
        return root / (tag or info_hash)
    short = tag[:8]
    stem = stem[: _MAX_NAME - len(short) - 3].strip(" .")
    return root / f"{stem} [{short}]"
