import os
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

import pytest

CLI_DIR = Path(__file__).parents[3]
SIZE = 200 << 20
RUNS = 3

pytestmark = pytest.mark.slow


@pytest.mark.skipif(shutil.which("curl") is None, reason="curl is not installed")
def test_four_connections_are_no_slower_than_1_1x_curl(serve, tmp_path: Path) -> None:
    # Each connection is limited to ~64 MB/s (1 ms per 64 KiB chunk), as a real server or link limits it, which
    # is what several connections are for. On an unlimited loopback curl moves GB/s and any Python client trails it.
    srv = serve(os.urandom(SIZE), delay=0.001)
    # warm the bytecode cache and the server's first-request costs
    subprocess.run(["curl", "-s", "-o", os.devnull, srv.url()], check=True)

    def timed(cmd: list[str]) -> float:
        t = time.perf_counter()
        subprocess.run(cmd, check=True, capture_output=True, cwd=CLI_DIR)
        return time.perf_counter() - t

    anydm_cmd = [sys.executable, "-c", "from src.main import main; main()", "get", srv.url(), "--quiet", "-c", "4"]
    curl, anydm = [], []
    for i in range(RUNS):  # interleaved, so machine noise hits both
        curl.append(timed(["curl", "-s", "-o", str(tmp_path / f"curl{i}"), srv.url()]))
        anydm.append(timed([*anydm_cmd, "-o", str(tmp_path / f"anydm{i}")]))
        (tmp_path / f"curl{i}").unlink()
    assert statistics.median(anydm) <= 1.1 * statistics.median(curl), (curl, anydm)
