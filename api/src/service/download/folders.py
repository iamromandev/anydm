"""Every folder is relative to ``DOWNLOAD_DIR``, and none may leave it.

One filesystem is what the disk guard watches; a folder outside it would be
written to without the guard ever seeing the space it takes.
"""

from __future__ import annotations

from pathlib import Path

from src.core.error import Error


def inside(root: Path, relative: str) -> Path:
    """``root / relative``, resolved; refused when it lands outside ``root``."""
    base = root.resolve()
    target = (base / relative).resolve()
    if not target.is_relative_to(base):
        raise Error.bad_request(message=f"Folder {relative!r} is outside the download folder")
    return target
