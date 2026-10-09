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

from src.data.repo.download.described import describe
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


def collection_folder(title: str, ref_id: str) -> str:
    """Where a collection's videos live, relative to ``DOWNLOAD_DIR``.

    Pure, like ``collection_dirname``: the same row always names the same
    directory, so it is derived on read rather than stored on the row.
    """
    return collection_relpath(None, title, ref_id)


def container_folder(collection: Any) -> str:
    """A collection's folder: stored since categories, else derived from its title and site id.

    Derived on read only for a collection from before categories, whose row has no folder.
    """
    if getattr(collection, "folder", None):
        return collection.folder
    described = describe(collection)
    return collection_folder(described.title, described.ref)


def standalone_folder(download_id: uuid.UUID) -> str:
    """Where a standalone download's finished file lived before categories: ``<download_id>``."""
    return str(download_id)


def placed_folder(download: Any) -> str:
    """Where a standalone download's file is: its stored folder, else the ``<download_id>`` folder from before categories."""
    return download.folder or standalone_folder(download.id)


def short_id(download_id: uuid.UUID) -> str:
    """The first eight characters of an id: what tells two files of one name apart."""
    return str(download_id)[:8]


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
