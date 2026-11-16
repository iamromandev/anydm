"""Interop with the real BitTorrent network. Opt-in: `uv run pytest -m live`, with ANYDM_LIVE_TORRENT set to a
magnet link or .torrent URL of something small and freely redistributable. Skipped by default, and without that variable."""

import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest

from tests.engine.torrent.cli_support import CLI_DIR

pytestmark = pytest.mark.live

SOURCE = os.environ.get("ANYDM_LIVE_TORRENT")


def cli(*args: str) -> subprocess.Popen[str]:
    env = {k: v for k, v in os.environ.items() if k != "ANYDM_DHT_BOOTSTRAP"}  # the built-in bootstrap nodes
    cmd = [sys.executable, "-c", "from src.main import main; main()", "get", *args]
    return subprocess.Popen(cmd, cwd=CLI_DIR, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)


@pytest.mark.skipif(not SOURCE, reason="set ANYDM_LIVE_TORRENT to a magnet link or .torrent URL")
def test_download_from_the_real_swarm_then_seed_it_until_ctrl_c(tmp_path: Path) -> None:
    assert SOURCE
    out = tmp_path / "dl"
    first = cli(SOURCE, "-o", str(out), "--quiet")
    stdout, stderr = first.communicate(timeout=900)
    assert first.returncode == 0, stderr
    paths = stdout.splitlines()
    assert paths and all(Path(p).is_file() for p in paths)

    seeding = cli(SOURCE, "-o", str(out), "--seed", "--quiet")
    try:
        time.sleep(15)
        assert seeding.poll() is None  # seeding: it does not exit by itself
    finally:
        seeding.send_signal(signal.SIGINT)
        stdout, stderr = seeding.communicate(timeout=60)
    assert seeding.returncode == 0, stderr
    assert stdout.splitlines() == paths
