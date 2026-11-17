"""The example plugin (cli/plugins/example) against real Docker: install, route, download, info.

Needs the image (`make build-example-plugin`, which `make test-docker` runs). The container reaches the fixture
server at host.docker.internal, which Docker Desktop provides (macOS only here)."""

import dataclasses
import hashlib
import json
import os
import random
import signal
import subprocess
import sys
import threading
import time
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
from src.main import app
from src.plugin import state
from typer.testing import CliRunner

pytestmark = pytest.mark.docker

IMAGE = os.environ.get("ANYDM_EXAMPLE_IMAGE", "anydm-example:latest")
CLI_DIR = Path(__file__).parents[2]
BODY = os.urandom(300_000)
BIG = os.urandom(1_500_000)  # /example/big is sent slowly (about 5 s), so a download can be interrupted half way
cli = CliRunner()


class Site(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: object) -> None:
        pass

    def _serve(self, body: bool) -> None:
        ok = self.path.startswith("/example/") and self.path.endswith(".bin")
        data = (BIG if self.path.startswith("/example/big") else BODY) if ok else b""
        self.send_response(200 if ok else 404)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if not body:
            return
        try:
            for i in range(0, len(data), 32768):
                self.wfile.write(data[i : i + 32768])
                self.wfile.flush()
                if self.path.startswith("/example/big"):
                    time.sleep(0.1)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    def do_GET(self) -> None:
        self._serve(True)

    def do_HEAD(self) -> None:
        self._serve(False)


def _docker(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["docker", *args], capture_output=True, text=True)


@pytest.fixture(scope="module", autouse=True)
def docker_and_image() -> None:
    if _docker("version").returncode != 0:
        pytest.skip("no Docker daemon")
    if sys.platform != "darwin":
        pytest.skip("the container reaches the fixture server at host.docker.internal (Docker Desktop)")
    if _docker("image", "inspect", IMAGE).returncode != 0:
        pytest.fail(f"image {IMAGE} not found: run `make build-example-plugin` (make test-docker does)")


@pytest.fixture
def host() -> Iterator[str]:
    """Base URL of the fixture site as the container sees it."""
    srv = ThreadingHTTPServer(("0.0.0.0", 0), Site)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://host.docker.internal:{srv.server_port}"
    srv.shutdown()
    srv.server_close()


def leftovers() -> list[str]:
    out = subprocess.run(["docker", "ps", "-a", "--filter", "label=anydm=plugin", "-q"],
                         capture_output=True, text=True, check=True)
    return out.stdout.split()


@pytest.fixture(autouse=True)
def no_containers_left() -> Iterator[None]:
    yield
    assert leftovers() == []


@pytest.fixture
def installed() -> None:
    result = cli.invoke(app, ["plugin", "add", IMAGE, "--yes"])
    assert result.exit_code == 0, result.output


def test_add_with_yes_installs_it_pinned_with_the_network_permission() -> None:
    result = cli.invoke(app, ["plugin", "add", IMAGE, "--yes"])
    assert result.exit_code == 0, result.output
    [plugin] = state.load()
    assert plugin.name == "example"
    assert plugin.capabilities == {"check", "download", "info"}
    assert plugin.permissions == {"network"}
    assert plugin.image != IMAGE and "sha256:" in plugin.image


def test_get_routes_to_the_plugin_and_the_file_is_owned_by_the_caller(
    installed: None, host: str, tmp_path: Path
) -> None:
    result = cli.invoke(app, ["get", f"{host}/example/abc", "-o", str(tmp_path), "--quiet"])
    assert result.exit_code == 0, result.output
    path = tmp_path / "abc.bin"
    assert result.stdout.strip() == str(path)
    assert path.read_bytes() == BODY
    assert path.stat().st_uid == os.getuid()
    assert not list(tmp_path.glob("*.part"))


def test_info_shows_the_title_and_size_from_the_plugin(installed: None, host: str) -> None:
    result = cli.invoke(app, ["info", f"{host}/example/abc", "--json"])
    assert result.exit_code == 0, result.output
    shown = json.loads(result.stdout)
    assert shown["title"] == "abc"
    assert shown["size"] == len(BODY)


def test_the_plugin_refuses_a_link_that_is_not_its_own(installed: None, host: str, tmp_path: Path) -> None:
    result = cli.invoke(app, ["get", f"{host}/other/abc", "--plugin", "example", "-o", str(tmp_path), "--quiet"])
    assert result.exit_code == 1
    assert "not an example link" in result.output
    assert list(tmp_path.iterdir()) == []


def test_without_the_network_permission_the_download_fails(installed: None, host: str, tmp_path: Path) -> None:
    [plugin] = state.load()
    state.save([dataclasses.replace(plugin, permissions=frozenset())])
    result = cli.invoke(app, ["get", f"{host}/example/abc", "-o", str(tmp_path), "--quiet", "--plugin", "example"])
    assert result.exit_code == 1
    assert not (tmp_path / "abc.bin").exists()


def start_cli(dest: Path, url: str) -> subprocess.Popen[str]:
    return subprocess.Popen(
        [sys.executable, "-c", "from src.main import main; main()", "get", url, "--plugin", "example",
         "-o", str(dest), "--quiet"],
        cwd=CLI_DIR, env=dict(os.environ), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )


@pytest.mark.slow
def test_check_takes_under_1_5_seconds_per_plugin_once_the_image_is_warm(installed: None) -> None:
    """NFR2: routing asks every installed plugin, so one `check` has to be cheap."""
    from src.plugin.runner import PluginRunner

    [plugin] = state.load()
    docker = PluginRunner("docker", "unused")
    docker.call(plugin, "check", "http://example.test/example/warm")  # warm: image layers, daemon
    start = time.monotonic()
    [line] = [x for x in docker.call(plugin, "check", "http://example.test/example/abc") if x["type"] == "supported"]
    assert line["value"] is True
    assert time.monotonic() - start < 1.5


@pytest.mark.slow
def test_ten_random_sigint_interrupts_leave_no_container_and_the_file_still_completes(
    installed: None, host: str, tmp_path: Path
) -> None:
    """NFR3, NFR5: Ctrl-C at any moment (before the container exists, mid download, as it ends) leaves no
    `anydm=plugin` container behind and no half-written file under the final name."""
    dest = tmp_path / "out"
    url = f"{host}/example/big"
    whole = hashlib.sha256(BIG).digest()
    rng = random.Random(11)
    for n in range(10):
        proc = start_cli(dest, url)
        time.sleep(rng.uniform(0.3, 3.5))
        proc.send_signal(signal.SIGINT)  # a no-op when the run has already ended
        _, err = proc.communicate(timeout=15)
        code = proc.returncode
        final = dest / "big.bin"
        if code == -signal.SIGINT and final.exists():  # the signal landed as a finished run's interpreter exited
            code = 0
        assert code in (0, 130), (n, code, err)
        assert leftovers() == [], (n, err)
        if code == 0:
            assert hashlib.sha256(final.read_bytes()).digest() == whole, n
        else:
            assert not final.exists(), n  # only big.bin.part may be there
    proc = start_cli(dest, url)
    _, err = proc.communicate(timeout=60)
    assert proc.returncode == 0, err
    assert hashlib.sha256((dest / "big.bin").read_bytes()).digest() == whole


@pytest.mark.slow
def test_a_sigkill_mid_download_leaves_a_container_the_next_run_reaps_and_the_file_completes(
    installed: None, host: str, tmp_path: Path
) -> None:
    """NFR3: SIGKILL gives anydm no chance to clean up. The container is still there afterwards, and the next
    run removes it (reap_stale) before finishing the download with the right content."""
    dest = tmp_path / "out"
    url = f"{host}/example/big"
    proc = start_cli(dest, url)
    deadline = time.monotonic() + 30
    while not leftovers() and time.monotonic() < deadline:
        time.sleep(0.1)
    assert leftovers(), "the plugin container never started"
    time.sleep(0.5)  # mid download
    proc.kill()
    proc.communicate(timeout=15)
    assert proc.returncode == -signal.SIGKILL
    assert not (dest / "big.bin").exists()
    stale = leftovers()
    assert stale, "the container should outlive its killed owner"
    proc = start_cli(dest, url)
    _, err = proc.communicate(timeout=60)
    assert proc.returncode == 0, err
    assert hashlib.sha256((dest / "big.bin").read_bytes()).digest() == hashlib.sha256(BIG).digest()
    assert not set(stale) & set(leftovers())
