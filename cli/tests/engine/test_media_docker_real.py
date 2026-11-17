"""PluginEngine against real Docker, the local media image (`make build-media`) and a local HTTP server.

The container reaches the server at host.docker.internal, which Docker Desktop provides (macOS only here)."""

import hashlib
import os
import random
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
from src.config.settings import default_media_image
from src.core.errors import DownloadError
from src.core.progress import NullReporter
from src.engine import Job
from src.engine.media import MediaEngine
from src.plugin.engine import PluginEngine
from src.plugin.runner import PluginRunner, process_start

pytestmark = pytest.mark.docker

CLI_DIR = Path(__file__).parents[2]
IMAGE = os.environ.get("ANYDM_MEDIA_IMAGE", default_media_image())
PIN = "2026.10.26"
BODY = os.urandom(600_000)
SESSION = "5e0d7b3a91c4"  # the cookie value that the "login" pages of the fixture server want
LAUNCH = "import src.engine as e; e._has_local_yt_dlp = lambda: False; from src.main import main; main()"


class Media(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    slow = False  # trickle the body so a download can be interrupted half way

    def log_message(self, format: str, *args: object) -> None:
        pass

    def do_GET(self) -> None:
        if "login" in self.path and f"sid={SESSION}" not in (self.headers.get("Cookie") or ""):
            self.send_response(403, "Login required")  # the reason phrase is what yt-dlp prints
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.path.startswith("/feed.xml"):
            host = self.headers["Host"]
            items = "".join(
                f'<item><title>t{i}</title><guid>g{i}</guid><enclosure url="http://{host}/item{i}.mp4" '
                f'type="video/mp4" length="5"/></item>'
                for i in (1, 2, 3)
            )
            body = f'<?xml version="1.0"?><rss version="2.0"><channel><title>pl</title>{items}</channel></rss>'.encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/rss+xml")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        data = BODY if self.path.startswith("/big") else b"hello"
        start, status = 0, 200
        m = re.fullmatch(r"bytes=(\d+)-", self.headers.get("Range") or "")
        if m:
            start, status = int(m.group(1)), 206
            if start >= len(data):  # nothing left to send: RFC 9110 says 416 and the size, not an empty 206
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{len(data)}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
        body = data[start:]
        self.send_response(status)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{len(data) - 1}/{len(data)}")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            for i in range(0, len(body), 32768):
                self.wfile.write(body[i : i + 32768])
                self.wfile.flush()
                if Media.slow and self.path.startswith("/big"):
                    time.sleep(0.15)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True


@pytest.fixture(scope="module")
def runner() -> PluginRunner:
    r = PluginRunner("docker", IMAGE)
    if not r.available():
        pytest.skip("no Docker daemon")
    if sys.platform != "darwin":
        pytest.skip("the container reaches the fixture server at host.docker.internal (Docker Desktop)")
    if not r.image_present():
        pytest.fail(f"image {IMAGE} not found: run `make build-media` (make test-docker does)")
    return r


@pytest.fixture
def host(runner: PluginRunner) -> Iterator[str]:
    """Base URL of the fixture server as the container sees it."""
    srv = ThreadingHTTPServer(("0.0.0.0", 0), Media)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    Media.slow = False
    yield f"http://host.docker.internal:{srv.server_port}"
    srv.shutdown()
    srv.server_close()


def leftovers() -> list[str]:
    out = subprocess.run(
        ["docker", "ps", "-a", "--filter", "label=anydm=media", "-q"], capture_output=True, text=True, check=True
    )
    return out.stdout.split()


@pytest.fixture(autouse=True)
def no_containers_left() -> Iterator[None]:
    yield
    assert leftovers() == []


def job(dest: Path, source: str, **kw: object) -> Job:
    base: dict[str, object] = {"source": source, "dest_dir": dest, "connections": 4, "format": None,
                               "playlist": False, "seed": False, "insecure": False}
    return Job(**{**base, **kw})  # ty: ignore[invalid-argument-type]


def engine(runner: PluginRunner) -> PluginEngine:
    return PluginEngine(runner)


def test_image_runs_as_non_root_with_the_pinned_yt_dlp(runner: PluginRunner) -> None:
    uid = subprocess.run(["docker", "run", "--rm", "--entrypoint", "id", IMAGE, "-u"],
                         capture_output=True, text=True, check=True).stdout.strip()
    version = subprocess.run(["docker", "run", "--rm", "--entrypoint", "yt-dlp", IMAGE, "--version"],
                             capture_output=True, text=True, check=True).stdout.strip()
    assert uid != "0"
    assert version == PIN


def test_check_tells_a_media_site_from_a_plain_file(runner: PluginRunner, host: str) -> None:
    assert runner.supported("https://www.youtube.com/watch?v=jNQXAC9IVRw") is True
    assert runner.supported(f"{host}/a.bin") is False


async def test_download_is_owned_by_the_caller(runner: PluginRunner, host: str, tmp_path: Path) -> None:
    paths = await engine(runner).run(job(tmp_path, f"{host}/video.mp4"), NullReporter())
    [path] = paths
    assert path.read_bytes() == b"hello"
    assert path.stat().st_uid == os.getuid()


async def test_format_reaches_yt_dlp(runner: PluginRunner, host: str, tmp_path: Path) -> None:
    with pytest.raises(DownloadError, match="format"):
        await engine(runner).run(job(tmp_path, f"{host}/video.mp4", format="no-such-format"), NullReporter())


async def test_a_playlist_gives_one_file_without_the_flag_and_every_item_with_it(
    runner: PluginRunner, host: str, tmp_path: Path
) -> None:
    one = await engine(runner).run(job(tmp_path / "one", f"{host}/feed.xml"), NullReporter())
    every = await engine(runner).run(job(tmp_path / "all", f"{host}/feed.xml", playlist=True), NullReporter())
    assert len(one) == 1
    assert len(every) == 3


async def test_files_in_the_output_folder_cannot_hijack_the_runner(
    runner: PluginRunner, host: str, tmp_path: Path
) -> None:
    """A random.py or src/ in the folder anydm saves into must not be imported by the container."""
    dest = tmp_path / "out"
    (dest / "src" / "engine").mkdir(parents=True)
    for hostile in (dest / "random.py", dest / "json.py", dest / "src" / "__init__.py",
                    dest / "src" / "engine" / "__init__.py", dest / "src" / "engine" / "media_runner.py"):
        hostile.write_text("raise SystemExit(99)\n")
    paths = await engine(runner).run(job(dest, f"{host}/video.mp4"), NullReporter())
    assert len(paths) == 1
    assert paths[0].read_bytes() == b"hello"
    assert runner.supported(f"{host}/a.bin") is False


def start_cli(dest: Path, url: str, home: Path, *extra: str) -> subprocess.Popen[str]:
    return subprocess.Popen(
        [sys.executable, "-c", LAUNCH, "get", url, "--kind", "media", "-o", str(dest), "--quiet", *extra],
        cwd=CLI_DIR, env={**os.environ, "HOME": str(home)}, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )


def wait_for_partial(dest: Path, proc: subprocess.Popen[str], timeout: float = 40) -> None:
    deadline = time.monotonic() + timeout
    while not any(dest.glob("*.part")):
        assert proc.poll() is None, proc.communicate()
        assert time.monotonic() < deadline, "no partial file appeared"
        time.sleep(0.05)


def interrupt(proc: subprocess.Popen[str]) -> float:
    t = time.monotonic()
    proc.send_signal(signal.SIGINT)
    proc.communicate(timeout=15)
    return time.monotonic() - t


@pytest.mark.slow
async def test_sigint_stops_the_container_in_time_keeps_the_partial_file_and_a_rerun_finishes(
    runner: PluginRunner, host: str, tmp_path: Path
) -> None:
    Media.slow = True
    dest = tmp_path / "out"
    proc = start_cli(dest, f"{host}/big.mp4", tmp_path / "home")
    wait_for_partial(dest, proc)
    took = interrupt(proc)
    assert proc.returncode == 130
    assert took < 2
    assert leftovers() == []
    assert any(dest.glob("*.part"))
    Media.slow = False
    [path] = await engine(runner).run(job(dest, f"{host}/big.mp4"), NullReporter())
    assert hashlib.sha256(path.read_bytes()).digest() == hashlib.sha256(BODY).digest()


def labelled(pid: int, started: str, token: str) -> str:
    out = subprocess.run(
        ["docker", "run", "-d", "--label", "anydm=media", "--label", f"anydm.pid={pid}",
         "--label", f"anydm.started={started}", "--label", f"anydm.token={token}",
         "--entrypoint", "sleep", IMAGE, "60"],
        capture_output=True, text=True, check=True)
    return out.stdout.strip()


def test_a_stale_container_is_removed_and_a_live_one_kept(runner: PluginRunner) -> None:
    gone = subprocess.Popen([sys.executable, "-c", "pass"])
    gone.wait()
    other = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
    stale = labelled(gone.pid, "x", "t1")
    reused = labelled(other.pid, "not the start time of that process", "t2")
    live = labelled(other.pid, process_start(other.pid), "t3")
    try:
        runner.reap_stale()
        left = [c[:12] for c in leftovers()]
        assert live[:12] in left
        assert stale[:12] not in left
        assert reused[:12] not in left
    finally:
        other.kill()
        other.wait()
        subprocess.run(["docker", "rm", "-f", stale, reused, live], capture_output=True)


@pytest.mark.slow
def test_check_and_download_start_add_little_over_the_in_process_path(
    runner: PluginRunner, host: str, tmp_path: Path
) -> None:
    import asyncio

    runner.supported(f"{host}/warm.bin")  # warm: image layers, daemon
    t = time.monotonic()
    runner.supported(f"{host}/a.bin")
    assert time.monotonic() - t < 1.5

    async def timed(eng: object, dest: Path, base: str) -> float:
        start = time.monotonic()
        await eng.run(job(dest, f"{base}/timing.mp4"), NullReporter())  # ty: ignore[unresolved-attribute]
        return time.monotonic() - start

    port = host.rsplit(":", 1)[1]
    asyncio.run(timed(engine(runner), tmp_path / "w1", host))
    asyncio.run(timed(MediaEngine(), tmp_path / "w2", f"http://127.0.0.1:{port}"))
    box = asyncio.run(timed(engine(runner), tmp_path / "c", host))
    local = asyncio.run(timed(MediaEngine(), tmp_path / "l", f"http://127.0.0.1:{port}"))
    assert box - local < 2, (box, local)


@pytest.mark.parametrize("delay", [0.1, 0.2, 0.3, 0.4])
async def test_a_cancel_right_after_the_start_takes_under_2_seconds_and_leaves_no_container(
    runner: PluginRunner, host: str, tmp_path: Path, delay: float
) -> None:
    """Early in its life the container may not exist yet: `docker kill` fails, so `rm -f` has to take over."""
    import asyncio

    Media.slow = True
    task = asyncio.create_task(engine(runner).run(job(tmp_path, f"{host}/big.mp4"), NullReporter()))
    await asyncio.sleep(delay)
    start = time.monotonic()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert time.monotonic() - start < 2
    assert leftovers() == []


@pytest.mark.slow
async def test_ten_random_interrupts_leave_no_container_and_the_file_still_completes(
    runner: PluginRunner, host: str, tmp_path: Path
) -> None:
    Media.slow = True
    dest = tmp_path / "out"
    rng = random.Random(7)
    for n in range(10):
        proc = start_cli(dest, f"{host}/big.mp4", tmp_path / "home")
        time.sleep(rng.uniform(1.0, 4.0))
        proc.send_signal(signal.SIGINT)  # a no-op when a resumed run has already ended
        _, err = proc.communicate(timeout=15)
        code = proc.returncode
        parts = {f.name: f.stat().st_size for f in dest.iterdir()} if dest.exists() else {}
        whole = hashlib.sha256(BODY).digest()
        done = [f for f in dest.glob("*.mp4") if hashlib.sha256(f.read_bytes()).digest() == whole]
        # 0: the (resumed) run finished before the signal came. 130: the interrupt. -SIGINT: the signal landed while
        # the interpreter of a run that had just finished was exiting (the file is complete). Anything else, 1 above
        # all, is a run that failed by itself, and its stderr says why.
        if code == -signal.SIGINT and done:
            code = 0
        assert code in (0, 130), (n, code, err, parts)
        assert leftovers() == [], (n, err)
        if code == 0:
            assert len(done) == 1, (n, err, parts)
            shutil.rmtree(dest)
    Media.slow = False
    [path] = await engine(runner).run(job(dest, f"{host}/big.mp4"), NullReporter())
    assert hashlib.sha256(path.read_bytes()).digest() == hashlib.sha256(BODY).digest()


def cookie_file(folder: Path) -> Path:
    f = folder / "cookies.txt"
    f.write_text(f"# Netscape HTTP Cookie File\nhost.docker.internal\tFALSE\t/\tFALSE\t2000000000\tsid\t{SESSION}\n")
    f.chmod(0o600)
    return f


def sha(path: Path) -> bytes:
    return hashlib.sha256(path.read_bytes()).digest()


async def test_a_login_page_downloads_with_cookies_and_leaves_the_file_and_the_folder_clean(
    runner: PluginRunner, host: str, tmp_path: Path
) -> None:
    cookies = cookie_file(tmp_path)
    before = sha(cookies)
    dest = tmp_path / "out"
    [path] = await engine(runner).run(job(dest, f"{host}/login.mp4", cookies=cookies), NullReporter())
    assert path.read_bytes() == b"hello"
    assert sha(cookies) == before
    assert [f.name for f in dest.iterdir() if "cookies" in f.name or SESSION in f.read_text(errors="replace")] == []


async def test_a_login_page_without_cookies_fails_with_the_hint(
    runner: PluginRunner, host: str, tmp_path: Path
) -> None:
    with pytest.raises(DownloadError, match="--cookies") as e:
        await engine(runner).run(job(tmp_path, f"{host}/login.mp4"), NullReporter())
    assert "pass --cookies FILE" in str(e.value)


async def test_stale_cookies_fail_with_the_expired_hint_and_the_file_is_unchanged(
    runner: PluginRunner, host: str, tmp_path: Path
) -> None:
    cookies = tmp_path / "stale.txt"
    cookies.write_text("# Netscape HTTP Cookie File\nhost.docker.internal\tFALSE\t/\tFALSE\t2000000000\tsid\told\n")
    before = sha(cookies)
    with pytest.raises(DownloadError, match="may be expired"):
        await engine(runner).run(job(tmp_path / "o", f"{host}/login.mp4", cookies=cookies), NullReporter())
    assert sha(cookies) == before


async def test_the_container_cannot_write_to_the_cookies_file(runner: PluginRunner, tmp_path: Path) -> None:
    """The mount is read-only: even a process that tries gets an error, and the file stays as it was."""
    cookies = cookie_file(tmp_path)
    before = sha(cookies)
    args = runner.base_args(None, cookies)
    done = subprocess.run(
        ["docker", *args[:-1], "--entrypoint", "sh", "--", IMAGE, "-c", "echo x >> /run/anydm/cookies.txt"],
        capture_output=True, text=True,
    )
    assert done.returncode != 0
    assert sha(cookies) == before


@pytest.mark.slow
async def test_sigint_with_cookies_keeps_the_file_unchanged_and_a_rerun_finishes(
    runner: PluginRunner, host: str, tmp_path: Path
) -> None:
    Media.slow = True
    cookies = cookie_file(tmp_path)
    before = sha(cookies)
    dest = tmp_path / "out"
    proc = start_cli(dest, f"{host}/big-login.mp4", tmp_path / "home", "--cookies", str(cookies))
    wait_for_partial(dest, proc)
    interrupt(proc)
    assert proc.returncode == 130
    assert leftovers() == []
    assert sha(cookies) == before
    Media.slow = False
    [path] = await engine(runner).run(job(dest, f"{host}/big-login.mp4", cookies=cookies), NullReporter())
    assert sha(path) == hashlib.sha256(BODY).digest()
    assert sha(cookies) == before
