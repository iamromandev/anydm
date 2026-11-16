"""MediaEngine against the real yt-dlp, a local fixture extractor and a local HTTP server. No network."""

import os
import signal
import subprocess
import sys
import threading
import time
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import pytest
import yt_dlp
from src.core.errors import DownloadError
from src.core.progress import NullReporter
from src.engine import Job
from src.engine.media import MediaEngine
from yt_dlp.extractor.common import InfoExtractor
from yt_dlp.globals import extractors as extractor_registry

CLI_DIR = Path(__file__).parents[2]
TITLES: dict[str, str] = {}


class _Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path.startswith("/stall"):
            self.send_response(200)
            self.send_header("Content-Length", "10")
            self.end_headers()
            self.wfile.flush()
            time.sleep(60)
            return
        if self.path.startswith("/missing"):
            self.send_error(404)
            return
        body = b"hello"
        self.send_response(200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:
        pass


@pytest.fixture
def base() -> Iterator[str]:
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_port}"
    srv.shutdown()


class FixtureIE(InfoExtractor):
    _VALID_URL = r"fixture:(?P<kind>[a-z]+)(?::(?P<arg>.*))?"
    IE_NAME = "fixture"
    base = ""

    def _entry(self, n: int, title: str, path: str = "v.mp4") -> dict[str, Any]:
        return {"id": f"id{n}", "title": title, "url": f"{self.base}/{path}", "ext": "mp4"}

    def _real_extract(self, url: str) -> dict[str, Any]:
        kind, arg = self._match_valid_url(url).group("kind", "arg")
        if kind == "single":
            return self._entry(1, arg)
        if kind == "list":
            return {"_type": "playlist", "id": "pl", "title": "pl",
                    "entries": [self._entry(i, f"t{i}") for i in range(1, 4)]}
        paths = ["v.mp4", "missing/v.mp4", "v.mp4"]
        return {"_type": "playlist", "id": "pl", "title": "pl",
                "entries": [self._entry(i, f"t{i}", p) for i, p in enumerate(paths, 1)]}


@pytest.fixture(autouse=True)
def real(monkeypatch: pytest.MonkeyPatch, base: str) -> None:
    FixtureIE.base = base
    monkeypatch.setitem(extractor_registry.value, "FixtureIE", FixtureIE)
    real_ydl = yt_dlp.YoutubeDL

    class YDL(real_ydl):
        def __init__(self, params: dict[str, Any] | None = None, *a: Any, **kw: Any) -> None:
            super().__init__(params, *a, **kw)
            self._ies = {"Fixture": FixtureIE, **self._ies}

    monkeypatch.setattr(yt_dlp, "YoutubeDL", YDL)


def job(dest: Path, source: str, **kw: Any) -> Job:
    b: dict[str, Any] = {"source": source, "dest_dir": dest, "connections": 4, "format": None,
                         "playlist": False, "seed": False, "insecure": False}
    return Job(**{**b, **kw})


async def test_pure_playlist_downloads_one_file_without_playlist_flag(tmp_path: Path) -> None:
    paths = await MediaEngine().run(job(tmp_path, "fixture:list"), NullReporter())
    assert len(paths) == 1


async def test_pure_playlist_downloads_every_item_with_playlist_flag(tmp_path: Path) -> None:
    paths = await MediaEngine().run(job(tmp_path, "fixture:list", playlist=True), NullReporter())
    assert len(paths) == 3


@pytest.mark.parametrize("title", ["../../x", "/abs/y"])
async def test_hostile_titles_end_up_inside_dest(tmp_path: Path, title: str) -> None:
    dest = tmp_path / "a" / "b"
    dest.mkdir(parents=True)
    paths = await MediaEngine().run(job(dest, f"fixture:single:{title}"), NullReporter())
    assert len(paths) == 1
    assert paths[0].is_relative_to(dest.resolve()) and paths[0].is_file()
    assert not (tmp_path / "x [id1].mp4").exists()
    assert not (tmp_path / "a" / "x [id1].mp4").exists()


async def test_symlink_pointing_outside_dest_is_rejected_before_writing(tmp_path: Path) -> None:
    dest = tmp_path / "dest"
    dest.mkdir()
    target = tmp_path / "victim"
    target.write_text("keep")
    (dest / "s [id1].mp4").symlink_to(target)
    with pytest.raises(DownloadError, match="outside"):
        await MediaEngine().run(job(dest, "fixture:single:s"), NullReporter())
    assert target.read_text() == "keep"


async def test_failed_entry_in_playlist_is_partial(tmp_path: Path) -> None:
    from src.core.errors import PartialDownload

    with pytest.raises(PartialDownload) as ei:
        await MediaEngine().run(job(tmp_path, "fixture:flaky", playlist=True), NullReporter())
    assert len(ei.value.paths) == 2


def test_only_one_error_line_on_stderr(tmp_path: Path, base: str) -> None:
    out = subprocess.run(
        [sys.executable, "-c", "from src.main import main; main()", "get", f"{base}/missing/v.mp4",
         "--kind", "media", "-o", str(tmp_path / "o")],
        cwd=CLI_DIR, env={**os.environ, "HOME": str(tmp_path / "home")}, capture_output=True, text=True, timeout=60,
    )
    assert out.returncode == 1
    assert len([ln for ln in out.stderr.splitlines() if "ERROR" in ln.upper() or ln.startswith("error:")]) == 1, out.stderr


@pytest.mark.slow
def test_sigint_against_stalled_server_exits_130_quickly(tmp_path: Path, base: str) -> None:
    proc = subprocess.Popen(
        [sys.executable, "-c", "from src.main import main; main()", "get", f"{base}/stall.mp4",
         "--kind", "media", "-o", str(tmp_path / "o"), "--quiet"],
        cwd=CLI_DIR, env={**os.environ, "HOME": str(tmp_path / "home")},
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    time.sleep(3)  # import yt-dlp, connect, stall
    t = time.monotonic()
    proc.send_signal(signal.SIGINT)
    proc.communicate(timeout=10)
    assert proc.returncode == 130
    assert time.monotonic() - t < 2
