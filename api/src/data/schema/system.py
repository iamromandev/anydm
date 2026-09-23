"""What the API reports about the machine it runs on."""

from __future__ import annotations

from src.core.base import BaseSchema


class DiskSchema(BaseSchema):
    """Space on the filesystem holding ``DOWNLOAD_DIR``, and the floor it must keep."""

    path: str
    total_bytes: int
    free_bytes: int
    min_free_bytes: int
