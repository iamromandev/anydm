"""Where a download's bytes are worked on: ``<downloads>/<download_id>/<filename>``.

Namespacing by download id is what makes cancel a directory removal and keeps two
downloads of the same video from colliding. It also matches the Bun API's
layout, so the torrent port can reuse it.
"""

from __future__ import annotations

import shutil
import uuid
from pathlib import Path
from typing import Any

from src.lib.folder import collection_dirname


def work_dir(root: Path, download_id: uuid.UUID) -> Path:
    return root / str(download_id)


def part_path(root: Path, download_id: uuid.UUID, name: str) -> Path:
    return work_dir(root, download_id) / f"{name}.part"


def final_path(root: Path, download_id: uuid.UUID, filename: str) -> Path:
    """The finished file.

    ``Path(filename).name`` is not decoration: ``filename`` is derived from a
    video title, and a title containing slashes must not be able to place a
    file outside the download's own directory.
    """
    return work_dir(root, download_id) / Path(filename).name


def collection_relpath(base: str | None, title: str, ref_id: str) -> str:
    """A collection's directory, relative to ``DOWNLOAD_DIR``: its folder's ``save_dir``, then its own name."""
    return str(Path(base or "") / collection_dirname(title, ref_id))


async def collection_path(collection: Any) -> str:
    """``collection_relpath`` for a collection row, reading its folder when it has one."""
    folder_id = getattr(collection, "folder_id", None)
    base = (await collection.folder).save_dir if folder_id is not None else None
    return collection_relpath(base, collection.title, collection.ref_id)


def collection_destination(root: Path, folder: str, filename: str, video_id: str) -> Path:
    """Where a collection's video ends up: its collection's folder, under its own name
    unless that is taken, when the video's id tells the two apart (v0.5)."""
    target = root / folder / Path(filename).name
    if target.exists():
        target = target.with_name(f"{target.stem}_{video_id}{target.suffix}")
    return target


def remove_work_files(root: Path, download_id: uuid.UUID) -> None:
    """Delete a download's whole work directory. Used by cancel."""
    shutil.rmtree(work_dir(root, download_id), ignore_errors=True)
