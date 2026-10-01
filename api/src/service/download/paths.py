"""Where a download's bytes are worked on: ``<downloads>/<download_id>/<filename>``.

Namespacing by download id is what makes cancel a directory removal and keeps two
downloads of the same video from colliding. It also matches the Bun API's
layout, so the torrent port can reuse it.
"""

from __future__ import annotations

import uuid
from pathlib import Path


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


def collection_destination(root: Path, folder: str, filename: str, video_id: str) -> Path:
    """Where a collection's video ends up: its collection's folder, under its own name
    unless that is taken, when the video's id tells the two apart (v0.5)."""
    target = root / folder / Path(filename).name
    if target.exists():
        target = target.with_name(f"{target.stem}_{video_id}{target.suffix}")
    return target
