import hashlib
import os
import random
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest

CLI_DIR = Path(__file__).parents[3]
SIZE = 8 << 20
RUNS = 20

pytestmark = pytest.mark.slow


def _cmd(url: str, out: Path) -> list[str]:
    return [sys.executable, "-c", "from src.main import main; main()", "get", url, "-o", str(out), "--quiet"]


def test_sigkill_at_random_points_then_resume_is_byte_identical(serve, tmp_path: Path) -> None:
    data = os.urandom(SIZE)
    want = hashlib.sha256(data).hexdigest()
    srv = serve(data, delay=0.01)
    rng = random.Random(1234)
    for i in range(RUNS):
        out = tmp_path / f"run{i}"
        srv.sent = 0
        srv.delay = 0.01
        proc = subprocess.Popen(_cmd(srv.url(), out), cwd=CLI_DIR, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        target = rng.uniform(0.02, 0.9) * SIZE
        deadline = time.monotonic() + 30
        while srv.sent < target and proc.poll() is None and time.monotonic() < deadline:
            time.sleep(0.005)
        proc.send_signal(signal.SIGKILL)
        proc.wait()
        final = out / "file.bin"
        assert not final.exists() or hashlib.sha256(final.read_bytes()).hexdigest() == want, "incomplete final file"

        srv.delay = 0
        done = subprocess.run(_cmd(srv.url(), out), cwd=CLI_DIR, capture_output=True, text=True, timeout=60)
        assert done.returncode == 0, done.stderr
        files = sorted(p.name for p in out.iterdir())
        # a run killed after the rename left a finished file; the rerun then saved a second copy
        assert files[0] == "file.bin", files
        for name in files:
            assert hashlib.sha256((out / name).read_bytes()).hexdigest() == want, (i, name)
        assert all(not n.endswith((".part", ".json", ".lock")) for n in files), files
