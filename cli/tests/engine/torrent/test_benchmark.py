"""Throughput of the pure-Python client from a local seeder over loopback. The plan's target is 20 MB/s; it is
reported, not enforced: a slower machine gets a warning, never a failure."""

import os
import time
import warnings
from pathlib import Path

import pytest
from src.core.progress import NullReporter
from src.engine.torrent.session import Session
from src.engine.torrent.storage import Storage

from tests.engine.torrent.cli_support import Live

pytestmark = [pytest.mark.slow, pytest.mark.torrent]

TARGET_MB_S = 20.0
SIZE = 64 << 20


async def test_loopback_download_speed(tmp_path: Path, live) -> None:
    lv: Live = live({"big.bin": os.urandom(SIZE)}, piece_len=256 << 10)
    storage = Storage(lv.swarm.torrent, tmp_path / "dl")
    session = Session(
        lv.swarm.torrent, storage, set(), NullReporter(), b"-AD0001-abcdefghijkl", extra_peers=[("127.0.0.1", lv.swarm.seeder.port)]
    )
    session.torrent.tiers.clear()
    try:
        start = time.perf_counter()
        await session.run()
        rate = SIZE / (time.perf_counter() - start) / 1e6
    finally:
        storage.close()
    print(f"\nloopback throughput: {rate:.1f} MB/s (target {TARGET_MB_S:.0f})")
    assert (tmp_path / "dl" / "pack" / "big.bin").stat().st_size == SIZE
    if rate < TARGET_MB_S:
        warnings.warn(f"loopback throughput {rate:.1f} MB/s is below the {TARGET_MB_S:.0f} MB/s target", stacklevel=1)
