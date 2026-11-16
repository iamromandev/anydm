import os
import pty
import select
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest
from src import engine
from src.engine.http import resume
from src.main import app
from typer.testing import CliRunner

runner = CliRunner()
CLI_DIR = Path(__file__).parents[2]


@pytest.fixture(autouse=True)
def _env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    monkeypatch.delenv("ANYDM_CONNECTIONS", raising=False)
    monkeypatch.delenv("ANYDM_DOWNLOAD_DIR", raising=False)


def test_quiet_success_prints_only_absolute_paths_on_stdout(serve, tmp_path: Path) -> None:
    data = os.urandom(1000)
    srv = serve(data)
    out = tmp_path / "out"
    result = runner.invoke(app, ["get", srv.url(), "-o", str(out), "--quiet"])
    assert result.exit_code == 0
    assert result.stdout == f"{(out / 'file.bin').resolve()}\n"
    assert result.stderr == ""
    assert (out / "file.bin").read_bytes() == data


def test_uses_download_dir_from_env(serve, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    srv = serve(b"abc")
    monkeypatch.setenv("ANYDM_DOWNLOAD_DIR", str(tmp_path / "envdir"))
    result = runner.invoke(app, ["get", srv.url(), "--quiet"])
    assert result.exit_code == 0
    assert (tmp_path / "envdir" / "file.bin").read_bytes() == b"abc"


def test_404_exits_1_with_status_on_stderr(serve) -> None:
    srv = serve(b"x", missing={"/nope"})
    result = runner.invoke(app, ["get", srv.url("/nope"), "--quiet"])
    assert result.exit_code == 1
    assert "404" in result.stderr
    assert result.stdout == ""


def test_too_many_connections_exits_2_naming_the_setting(serve) -> None:
    srv = serve(b"x")
    result = runner.invoke(app, ["get", srv.url(), "-c", "99"])
    assert result.exit_code == 2
    assert "connections" in result.stderr
    assert srv.log == []


def test_unsupported_source_exits_2() -> None:
    result = runner.invoke(app, ["get", "ftp://x/y"])
    assert result.exit_code == 2
    assert result.stderr.startswith("error:")


def test_unwritable_output_exits_2_before_any_request(serve, tmp_path: Path) -> None:
    srv = serve(b"x")
    blocker = tmp_path / "file"
    blocker.write_text("not a folder")
    result = runner.invoke(app, ["get", srv.url(), "-o", str(blocker / "sub"), "--quiet"])
    assert result.exit_code == 2
    assert srv.log == []


def test_a_magnet_without_a_usable_infohash_exits_2() -> None:
    result = runner.invoke(app, ["get", "magnet:?dn=nothing", "--quiet"])
    assert result.exit_code == 2
    assert "infohash" in result.stderr


def test_keyboard_interrupt_exits_130(serve, monkeypatch: pytest.MonkeyPatch) -> None:
    class Interrupting:
        async def run(self, job, report):
            raise KeyboardInterrupt

    monkeypatch.setattr(engine, "engine_for", lambda kind: Interrupting())
    srv = serve(b"x")
    result = runner.invoke(app, ["get", srv.url(), "--quiet"])
    assert result.exit_code == 130


def test_progress_goes_to_stderr_on_a_tty(serve, tmp_path: Path) -> None:
    srv = serve(os.urandom(4 << 20), delay=0.05)
    out = tmp_path / "o"
    master, slave = pty.openpty()
    proc = subprocess.Popen(
        [sys.executable, "-c", "from src.main import main; main()", "get", srv.url(), "-o", str(out)],
        cwd=CLI_DIR,
        env={**os.environ, "HOME": str(tmp_path / "home")},
        stdout=subprocess.PIPE,
        stderr=slave,
        text=True,
    )
    os.close(slave)
    chunks = []
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:  # read while it runs: a closed pty can drop unread output
        if select.select([master], [], [], 0.1)[0]:
            try:
                chunk = os.read(master, 65536)
            except OSError:
                break
            if not chunk:
                break
            chunks.append(chunk)
        elif proc.poll() is not None:
            break
    stdout = proc.communicate(timeout=10)[0]
    os.close(master)
    stderr = b"".join(chunks).decode(errors="replace")
    assert proc.returncode == 0, stderr
    assert stdout.strip() == str((out / "file.bin").resolve())
    assert "100%" in stderr
    assert "/s" in stderr  # speed, shown while the download runs
    assert "0:00:00" in stderr  # the ETA column


def test_sigint_exits_130_and_keeps_partial_state(serve, tmp_path: Path) -> None:
    srv = serve(os.urandom(8 << 20), delay=0.05)
    out = tmp_path / "out"
    proc = subprocess.Popen(
        [sys.executable, "-c", "from src.main import main; main()", "get", srv.url(), "-o", str(out), "--quiet"],
        cwd=CLI_DIR,
        env={**os.environ, "HOME": str(tmp_path / "home")},
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    deadline = time.monotonic() + 20
    while srv.sent < 1 << 20 and time.monotonic() < deadline:
        time.sleep(0.05)
    proc.send_signal(signal.SIGINT)
    stdout, stderr = proc.communicate(timeout=20)
    assert proc.returncode == 130, stderr
    assert stdout == ""
    assert not (out / "file.bin").exists()
    assert (out / "file.bin.part.json").exists()


def test_non_tty_prints_notices_but_not_progress(serve, tmp_path: Path) -> None:
    srv = serve(os.urandom(1000))
    out = tmp_path / "out"
    out.mkdir()
    final = out / "file.bin"
    resume.part_path(final).write_bytes(b"x" * 1000)
    resume.write(final, resume.Record(srv.url(), srv.url(), 1000, srv.etag, srv.last_modified, []))
    result = runner.invoke(app, ["get", srv.url(), "-o", str(out)])
    assert result.exit_code == 0
    assert "cannot resume a single-stream download; starting over" in result.stderr
    assert "%" not in result.stderr


def test_rejected_source_leaves_no_output_folder(tmp_path: Path) -> None:
    out = tmp_path / "newdir"
    result = runner.invoke(app, ["get", "ftp://x/y", "-o", str(out)])
    assert result.exit_code == 2
    assert not out.exists()


def test_second_interrupt_during_cleanup_still_exits_130(serve, monkeypatch: pytest.MonkeyPatch) -> None:
    class Interrupting:
        async def run(self, job, report):
            raise KeyboardInterrupt

    def boom(self) -> None:
        raise KeyboardInterrupt

    monkeypatch.setattr(engine, "engine_for", lambda kind: Interrupting())
    monkeypatch.setattr("src.core.progress.NoticeReporter.close", boom)
    result = runner.invoke(app, ["get", serve(b"x").url()])
    assert result.exit_code == 130
