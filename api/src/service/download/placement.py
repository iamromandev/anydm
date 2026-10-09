"""Moving finished files between folders under DOWNLOAD_DIR, with what belongs beside them.

Everything stays on one filesystem, so each move is a rename.
"""

from __future__ import annotations

from pathlib import Path, PurePosixPath

from src.lib.media.sidecar import folder_listing, match_sidecars


def free_path(target: Path, tag: str) -> Path:
    """``target``, or the same name with ``_<tag>`` before its extension (a directory: at the end) when taken."""
    if not target.exists():
        return target
    if target.is_dir():
        return target.with_name(f"{target.name}_{tag}")
    return target.with_name(f"{target.stem}_{tag}{target.suffix}")


def move_file_with_sidecars(source: Path, folder: Path, tag: str) -> Path:
    """Move ``source`` into ``folder``, and the subtitle files that go with it, keeping their layout.

    A subtitle named after the file follows its new name when the clash rule renamed it.
    """
    sidecars = match_sidecars(source.name, folder_listing(source.parent))
    folder.mkdir(parents=True, exist_ok=True)
    target = free_path(folder / source.name, tag)
    source.replace(target)
    for sidecar in sidecars:
        relative = PurePosixPath(sidecar.path)
        name = relative.name
        if target.stem != source.stem and name.startswith(source.stem):
            name = target.stem + name[len(source.stem) :]
        destination = folder / relative.parent / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        (source.parent / relative).replace(destination)
    return target


def move_dir(source: Path, parent: Path, tag: str) -> Path:
    """Move the directory ``source`` under ``parent``, keeping its name unless that is taken."""
    parent.mkdir(parents=True, exist_ok=True)
    target = free_path(parent / source.name, tag)
    source.replace(target)
    return target
