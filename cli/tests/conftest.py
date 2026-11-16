import json
import re
import threading
import time
from collections.abc import Iterator
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest


@dataclass
class Request:
    method: str
    path: str
    range: str | None
    cookie: str | None = None


@dataclass
class FileServer:
    """A local HTTP server for one body, with switches for the behaviours the engine must handle."""

    data: bytes
    ranges: bool = True  # honour Range with 206 and advertise Accept-Ranges
    range_as_200: bool = False  # advertise ranges but answer every Range request with 200
    length: bool = True  # send Content-Length
    etag: str | None = '"v1"'
    last_modified: str | None = "Wed, 01 Jan 2025 00:00:00 GMT"
    disposition: str | None = None
    delay: float = 0.0  # seconds between 64 KiB chunks
    head: bool = True  # answer HEAD (otherwise 405)
    redirects: dict[str, str] = field(default_factory=dict)  # path -> target path
    missing: set[str] = field(default_factory=set)  # paths answered with 404
    log: list[Request] = field(default_factory=list)
    sent: int = 0
    port: int = 0

    def url(self, path: str = "/file.bin") -> str:
        return f"http://127.0.0.1:{self.port}{path}"

    def gets(self) -> list[Request]:
        return [r for r in self.log if r.method == "GET"]


def _handler(srv: FileServer) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format: str, *args: object) -> None:
            pass

        def _serve(self, send_body: bool) -> None:
            rng = self.headers.get("Range")
            srv.log.append(Request(self.command, self.path, rng, self.headers.get("Cookie")))
            path = self.path.split("?")[0]
            if path in srv.redirects:
                self.send_response(302)
                self.send_header("Location", srv.redirects[path])
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if path in srv.missing:
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.command == "HEAD" and not srv.head:
                self.send_response(405)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            data = srv.data
            start, end, status = 0, len(data) - 1, 200
            m = re.fullmatch(r"bytes=(\d+)-(\d*)", rng or "")
            if m and srv.ranges and not srv.range_as_200:
                start = int(m.group(1))
                end = min(int(m.group(2)), len(data) - 1) if m.group(2) else len(data) - 1
                status = 206
            self.send_response(status)
            if srv.ranges:
                self.send_header("Accept-Ranges", "bytes")
            if status == 206:
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
            if srv.etag:
                self.send_header("ETag", srv.etag)
            if srv.last_modified:
                self.send_header("Last-Modified", srv.last_modified)
            if srv.disposition:
                self.send_header("Content-Disposition", srv.disposition)
            body = data[start : end + 1]
            if srv.length:
                self.send_header("Content-Length", str(len(body)))
            else:
                self.send_header("Connection", "close")
                self.close_connection = True
            self.end_headers()
            if not send_body:
                return
            try:
                for i in range(0, len(body), 65536):
                    self.wfile.write(body[i : i + 65536])
                    srv.sent += len(body[i : i + 65536])
                    if srv.delay:
                        time.sleep(srv.delay)
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True

        def do_GET(self) -> None:
            self._serve(True)

        def do_HEAD(self) -> None:
            self._serve(False)

    return Handler


@pytest.fixture
def serve() -> Iterator:
    """`serve(data, **options)` starts a FileServer on 127.0.0.1; all are stopped after the test."""
    started: list[ThreadingHTTPServer] = []

    def make(data: bytes, **options: object) -> FileServer:
        srv = FileServer(data, **options)  # ty: ignore[invalid-argument-type]
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), _handler(srv))
        httpd.daemon_threads = True
        srv.port = httpd.server_address[1]
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        started.append(httpd)
        return srv

    yield make
    for httpd in started:
        httpd.shutdown()
        httpd.server_close()


@pytest.fixture(autouse=True)
def no_real_dht(monkeypatch: pytest.MonkeyPatch) -> None:
    """No test may reach the real DHT bootstrap nodes: with an empty list the DHT is off. Tests that want a
    DHT point this at a fake network on 127.0.0.1."""
    monkeypatch.setenv("ANYDM_DHT_BOOTSTRAP", "")


FAKE_DOCKER = Path(__file__).parent / "fixtures" / "fake_docker.py"


class FakeDocker:
    """The fake `docker` command: configure with `.set(...)`, read what it was asked with `.calls()`."""

    def __init__(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        self.script = tmp_path / "fake_docker.json"
        self.log = tmp_path / "fake_docker.log"
        self._cfg: dict[str, object] = {"log": str(self.log)}
        monkeypatch.setenv("FAKE_DOCKER_SCRIPT", str(self.script))
        monkeypatch.setenv("ANYDM_DOCKER", str(FAKE_DOCKER))
        self.set()

    def set(self, **cfg: object) -> None:
        self._cfg.update(cfg)
        self.script.write_text(json.dumps(self._cfg))

    def calls(self) -> list[list[str]]:
        if not self.log.exists():
            return []
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def commands(self) -> list[str]:
        return [c[0] for c in self.calls() if c]

    def marked(self, what: str) -> list[str]:
        """Container names that got `kill` (what="killed") or `rm -f` (what="removed")."""
        return [p.name.removeprefix(f"{self.log.name}.{what}.") for p in self.log.parent.glob(f"{self.log.name}.{what}.*")]


@pytest.fixture
def fake_docker(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[FakeDocker]:
    from src import engine

    engine._docker_runner.cache_clear()  # the runner remembers availability and answers per process
    yield FakeDocker(tmp_path, monkeypatch)
    engine._docker_runner.cache_clear()
