"""A folder named for what it holds: a playlist (v0.5).

Pure: the same row always names the same directory, so nothing has to
remember where anything went.
"""

from __future__ import annotations

import re

#: Path separators, characters Windows refuses, and control characters.
_UNSAFE = re.compile(r'[/\\<>:"|?*\x00-\x1f]')
_MAX_NAME = 150


def collection_dirname(title: str, ref_id: str) -> str:
    """A collection's directory name: its title and the site's id for it, ``Talks [PL1abc]``.

    The id keeps two playlists with one title apart.
    """
    stem = _UNSAFE.sub("_", title).strip(" .")[:_MAX_NAME].strip(" .")
    tag = _UNSAFE.sub("_", ref_id).strip(" .")[:64]
    return f"{stem} [{tag}]" if stem else tag
