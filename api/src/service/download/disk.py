"""How much room is left where downloads land, and refusing work that would not fit.

One filesystem is checked: ``DOWNLOAD_DIR``'s. ``TORRENT_DIR`` lives under it,
so rqbit's writes come out of the same free space.
"""

from __future__ import annotations

import errno
import shutil
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from loguru import logger

from src.core.error import Error
from src.core.type import Code, ErrorType

_GIB = 1024**3


@dataclass(frozen=True)
class DiskUsage:
    path: str
    total_bytes: int
    free_bytes: int
    min_free_bytes: int


def _gb(size: int) -> str:
    return f"{size / _GIB:.1f} GB"


def _insufficient(message: str) -> Error:
    # Retryable: space is freed by people, not by the passage of time, but a
    # task that waits for it is exactly what the worker should do.
    return Error.create(
        code=Code.INSUFFICIENT_STORAGE,
        message=message,
        error_type=ErrorType.INSUFFICIENT_STORAGE,
        retry_able=True,
    )


def is_insufficient_storage(error: Error) -> bool:
    return error.type == ErrorType.INSUFFICIENT_STORAGE


def storage_error(exc: OSError) -> Error | None:
    """The guard's error for a write the disk refused, or ``None`` for any other ``OSError``."""
    if exc.errno != errno.ENOSPC:
        return None
    return _insufficient("Not enough disk space: the disk filled up while writing")


class DiskGuard:
    def __init__(
        self,
        path: str,
        min_free_bytes: int,
        *,
        usage: Callable[[str], Any] = shutil.disk_usage,
    ) -> None:
        self._path = path
        self._min_free = min_free_bytes
        self._usage = usage

    @property
    def min_free_bytes(self) -> int:
        return self._min_free

    def usage(self) -> DiskUsage | None:
        """Current usage, or ``None`` when the filesystem cannot be read."""
        try:
            sample = self._usage(self._path)
        except OSError as exc:
            logger.warning("DiskGuard|cannot read disk usage of {}: {}", self._path, exc)
            return None
        return DiskUsage(
            path=self._path,
            total_bytes=sample.total,
            free_bytes=sample.free,
            min_free_bytes=self._min_free,
        )

    def require(self, extra_bytes: int = 0) -> None:
        """Raise a 507 unless ``extra_bytes`` fit with ``min_free_bytes`` still left over.

        A disk that cannot be read lets the work through: a broken check that
        stopped every download would be worse than the full disk it guards.
        """
        if self._min_free <= 0:
            return
        usage = self.usage()
        if usage is None or usage.free_bytes >= self._min_free + extra_bytes:
            return
        needs = f"{_gb(extra_bytes)} to write and " if extra_bytes else ""
        raise _insufficient(
            f"Not enough disk space: {_gb(usage.free_bytes)} free, "
            f"{needs}{_gb(self._min_free)} must stay free"
        )
