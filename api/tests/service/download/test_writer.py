import os
from pathlib import Path

import pytest
from src.service.download.writer import SegmentWriter

pytestmark = pytest.mark.asyncio


def _writer(dest: Path, total: int | None = None, **kwargs: object) -> SegmentWriter:
    options: dict[str, object] = {"buffer_bytes": 8, "flush_interval_ms": 1_000_000}
    options.update(kwargs)
    return SegmentWriter(dest, total, **options)  # ty: ignore[invalid-argument-type]


async def test_open_preallocates_to_the_full_size(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    writer = _writer(dest, 1024)
    await writer.open()
    await writer.close()
    assert dest.stat().st_size == 1024


async def test_no_total_means_no_preallocation(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    writer = _writer(dest, None)
    await writer.open()
    await writer.close()
    assert dest.stat().st_size == 0


async def test_segments_written_out_of_order_land_at_their_offsets(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    writer = _writer(dest, 8)
    await writer.open()
    await writer.write(1, 4, b"WXYZ")
    await writer.write(0, 0, b"ABCD")
    await writer.close()
    assert dest.read_bytes() == b"ABCDWXYZ"


async def test_the_watermark_only_moves_once_bytes_are_durable(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    writer = _writer(dest, 16, buffer_bytes=8)
    await writer.open()
    # Buffered, not written: the watermark must not claim these bytes, or a
    # crash here would resume past data that never reached the disk.
    assert await writer.write(0, 0, b"ABC") == 0
    # Crossing the buffer threshold flushes and the watermark catches up.
    assert await writer.write(0, 3, b"DEFGH") == 8
    await writer.close()


async def test_an_explicit_flush_moves_the_watermark(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    writer = _writer(dest, 16, buffer_bytes=1024)
    await writer.open()
    await writer.write(0, 0, b"ABC")
    assert await writer.flush(0) == 3
    await writer.close()
    assert dest.read_bytes()[:3] == b"ABC"


async def test_the_timer_flushes_a_buffer_that_never_fills(tmp_path: Path) -> None:
    """A size-only trigger leaves a slow download looking frozen."""
    ticks = iter([0.0, 0.0, 9.0, 9.0, 9.0])
    dest = tmp_path / "out.bin"
    writer = _writer(dest, 16, buffer_bytes=1 << 20, flush_interval_ms=500, clock=lambda: next(ticks))
    await writer.open()
    assert await writer.write(0, 0, b"AB") == 0
    assert await writer.write(0, 2, b"CD") == 4
    await writer.close()


async def test_a_write_past_the_segment_bound_is_truncated(tmp_path: Path) -> None:
    """One byte past `end` is the next segment's region."""
    dest = tmp_path / "out.bin"
    writer = SegmentWriter(dest, 8, {0: 3}, buffer_bytes=1, flush_interval_ms=1_000_000)
    await writer.open()
    assert await writer.write(0, 0, b"ABCDEFGH") == 4
    await writer.close()
    assert dest.read_bytes() == b"ABCD" + b"\x00" * 4


async def test_close_with_fsync_still_produces_the_bytes(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    writer = _writer(dest, 4)
    await writer.open()
    await writer.write(0, 0, b"ABCD")
    await writer.close(fsync=True)
    assert dest.read_bytes() == b"ABCD"


async def test_write_errors_propagate(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    writer = _writer(dest, 4)
    await writer.open()
    os.close(writer._fd)  # simulate the fd going away under us
    with pytest.raises(OSError):
        await writer.write(0, 0, b"ABCD")
        await writer.flush(0)
    writer._fd = -1  # close() must not double-close


async def test_high_water_reports_the_durable_offset_and_a_default(tmp_path: Path) -> None:
    dest = tmp_path / "out.bin"
    writer = _writer(dest, 16, buffer_bytes=1)
    await writer.open()
    await writer.write(0, 4, b"ABCD")
    assert writer.high_water(0) == 8
    # A segment nothing has written to yet has no opinion, so the caller's
    # resume position stands.
    assert writer.high_water(3, default=12) == 12
    await writer.close()
