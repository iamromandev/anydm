import statistics
import subprocess
import sys
import time
from pathlib import Path

CLI_DIR = Path(__file__).parents[1]
HEAVY = (
    "yt_dlp", "libtorrent", "src.engine.torrent", "src.engine.http", "httpx", "textual", "src.tui",
    "src.engine.docker", "src.engine.media_docker", "src.engine.media_runner",
)


def test_importing_main_does_not_load_heavy_backends() -> None:
    code = (
        "import sys; import src.main; "
        f"print([m for m in {HEAVY!r} if m in sys.modules])"
    )
    out = subprocess.run([sys.executable, "-c", code], cwd=CLI_DIR, capture_output=True, text=True, check=True)
    assert out.stdout.strip() == "[]"


def test_help_runs_under_300_ms() -> None:
    cmd = [sys.executable, "-c", "from src.main import main; main()", "--help"]
    subprocess.run(cmd, cwd=CLI_DIR, capture_output=True, check=True)  # warm the bytecode cache
    times = []
    for _ in range(5):
        t = time.perf_counter()
        subprocess.run(cmd, cwd=CLI_DIR, capture_output=True, check=True)
        times.append(time.perf_counter() - t)
    assert statistics.median(times) < 0.3, times


def test_resolving_non_media_sources_does_not_import_yt_dlp() -> None:
    code = (
        "import pathlib, sys, tempfile\n"
        "from src.engine import resolve\n"
        "from src.core.errors import UsageError\n"
        "f = pathlib.Path(tempfile.mkdtemp(), 'a.torrent'); f.write_bytes(b'x')\n"
        "resolve('magnet:?xt=urn:btih:abc', None)\n"
        "resolve(str(f), None)\n"
        "try:\n    resolve('ftp://h/a', None)\n"
        "except UsageError:\n    pass\n"
        "print('yt_dlp' in sys.modules)\n"
    )
    out = subprocess.run([sys.executable, "-c", code], cwd=CLI_DIR, capture_output=True, text=True, check=True)
    assert out.stdout.strip() == "False"
