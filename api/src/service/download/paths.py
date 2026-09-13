"""Where a task's bytes live: ``<downloads>/<task_id>/<filename>``.

Namespacing by task id is what makes cancel a directory removal and keeps two
downloads of the same video from colliding. It also matches the Bun API's
layout, so the torrent port can reuse it.
"""

from __future__ import annotations

import uuid
from pathlib import Path


def task_dir(root: Path, task_id: uuid.UUID) -> Path:
    return root / str(task_id)


def part_path(root: Path, task_id: uuid.UUID, name: str) -> Path:
    return task_dir(root, task_id) / f"{name}.part"


def final_path(root: Path, task_id: uuid.UUID, filename: str) -> Path:
    """The finished file.

    ``Path(filename).name`` is not decoration: ``filename`` is derived from a
    video title, and a title containing slashes must not be able to place a
    file outside the task's own directory.
    """
    return task_dir(root, task_id) / Path(filename).name
