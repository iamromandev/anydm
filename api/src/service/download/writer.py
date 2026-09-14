"""Positional writes into one preallocated file, buffered per segment.

Buffering is what makes segmented writing viable at all: a ``to_thread`` hop per
64 KiB chunk costs more than the blocking write it replaces. The buffer flushes
on whichever comes first, a size threshold or a timer — a size-only trigger
would leave a slow download looking frozen while megabytes sat unreported.

The watermark this returns counts bytes ``pwrite`` has accepted, never bytes
received from the socket. Resume reads that number, so a watermark ahead of the
disk would skip bytes that were never written.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import time
from collections.abc import Callable, Mapping
from pathlib import Path


class _Buffer:
    __slots__ = ("data", "flushed_at", "high_water", "start")

    def __init__(self, flushed_at: float) -> None:
        self.data = bytearray()
        self.start = 0
        self.high_water = 0
        self.flushed_at = flushed_at


def _preallocate(fd: int, total: int) -> None:
    """Reserve the blocks where the platform can, so a full disk fails now.

    ``posix_fallocate`` is Linux-only — which is where the container runs. On a
    macOS host ``ftruncate`` still produces a correctly sized sparse file; it
    just cannot promise the space is there.
    """
    fallocate = getattr(os, "posix_fallocate", None)
    if fallocate is not None:
        with contextlib.suppress(OSError):
            fallocate(fd, 0, total)
            return
    os.ftruncate(fd, total)


def _pwrite_all(fd: int, data: bytes, offset: int) -> None:
    """``pwrite`` is allowed to write less than it was given."""
    written = 0
    while written < len(data):
        written += os.pwrite(fd, data[written:], offset + written)


class SegmentWriter:
    def __init__(
        self,
        dest: Path,
        total_bytes: int | None,
        bounds: Mapping[int, int] | None = None,
        *,
        buffer_bytes: int,
        flush_interval_ms: int,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._dest = dest
        self._total = total_bytes
        self._bounds = bounds or {}
        self._buffer_bytes = buffer_bytes
        self._interval = flush_interval_ms / 1000
        self._clock = clock
        self._fd = -1
        self._buffers: dict[int, _Buffer] = {}

    async def open(self) -> None:
        self._dest.parent.mkdir(parents=True, exist_ok=True)
        self._fd = os.open(self._dest, os.O_RDWR | os.O_CREAT)
        if self._total is not None:
            await asyncio.to_thread(_preallocate, self._fd, self._total)

    async def write(self, index: int, offset: int, data: bytes) -> int:
        end = self._bounds.get(index)
        if end is not None:
            data = data[: max(0, end + 1 - offset)]

        buffer = self._buffers.get(index)
        if buffer is None:
            buffer = self._buffers[index] = _Buffer(self._clock())
            buffer.high_water = offset

        if not data:
            return buffer.high_water

        if not buffer.data:
            buffer.start = offset
        elif offset != buffer.start + len(buffer.data):
            await self.flush(index)
            buffer.start = offset

        buffer.data += data

        due = self._clock() - buffer.flushed_at >= self._interval
        if len(buffer.data) >= self._buffer_bytes or due:
            return await self.flush(index)
        return buffer.high_water

    async def flush(self, index: int) -> int:
        buffer = self._buffers.get(index)
        if buffer is None:
            return 0
        if buffer.data:
            payload = bytes(buffer.data)
            await asyncio.to_thread(_pwrite_all, self._fd, payload, buffer.start)
            buffer.high_water = buffer.start + len(payload)
            buffer.data.clear()
        buffer.flushed_at = self._clock()
        return buffer.high_water

    async def flush_all(self) -> None:
        for index in list(self._buffers):
            await self.flush(index)

    def high_water(self, index: int, default: int = 0) -> int:
        """The absolute offset one past this segment's last durable byte.

        ``default`` is what a segment that has written nothing reports — its
        resume position, so the caller's arithmetic never goes negative.
        """
        buffer = self._buffers.get(index)
        return buffer.high_water if buffer is not None else default

    async def close(self, *, fsync: bool = False) -> None:
        if self._fd < 0:
            return
        try:
            await self.flush_all()
            if fsync:
                await asyncio.to_thread(os.fsync, self._fd)
        finally:
            os.close(self._fd)
            self._fd = -1
