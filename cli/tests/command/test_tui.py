import contextlib
import fcntl
import os
import pty
import select
import signal
import struct
import subprocess
import sys
import termios
import time
from pathlib import Path

import pytest
from src.main import app
from typer.testing import CliRunner

runner = CliRunner()
CLI_DIR = Path(__file__).parents[2]


def test_help_exits_0() -> None:
    r = runner.invoke(app, ["tui", "--help"])
    assert r.exit_code == 0
    assert "queue" in r.output.lower()


@pytest.mark.parametrize("value", ["0", "11", "x"])
def test_invalid_max_active_exits_2_naming_the_setting(value: str, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANYDM_MAX_ACTIVE", value)
    r = runner.invoke(app, ["tui"])
    assert r.exit_code == 2
    assert "max_active" in r.output


def _spawn(tmp_path: Path) -> tuple[subprocess.Popen[bytes], int]:
    """Run the TUI on a pseudo-terminal. No fork in this (threaded) process: the child is an ordinary subprocess."""
    env = {**os.environ, "ANYDM_DOWNLOAD_DIR": str(tmp_path), "XDG_CONFIG_HOME": str(tmp_path), "TERM": "xterm-256color"}
    master, slave = pty.openpty()
    fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", 30, 100, 0, 0))
    try:
        proc = subprocess.Popen(
            [sys.executable, "-c", "from src.main import main; main()", "tui"],
            cwd=CLI_DIR,
            env=env,
            stdin=slave,
            stdout=slave,
            stderr=slave,
            start_new_session=True,
        )
    except BaseException:
        os.close(master)
        raise
    finally:
        os.close(slave)
    return proc, master


def _read_until(fd: int, proc: subprocess.Popen[bytes], timeout: float, marker: bytes | None = None) -> bytes:
    """Read the terminal output until `marker` shows up or the process ends; return what was read."""
    out = b""
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if select.select([fd], [], [], 0.05)[0]:
            with contextlib.suppress(OSError):
                out += os.read(fd, 65536)
        if marker is not None and marker in out:
            break
        if marker is None and proc.poll() is not None:
            break
    return out


@pytest.mark.slow
def test_sigint_exits_0_within_2_seconds(tmp_path: Path) -> None:
    proc, fd = _spawn(tmp_path)
    try:
        screen = _read_until(fd, proc, 20, b"Quit")  # the footer: the first screen is up and ready for keys
        assert b"Quit" in screen, screen[-300:]
        assert proc.poll() is None
        proc.send_signal(signal.SIGINT)
        _read_until(fd, proc, 2)
        assert proc.wait(timeout=0.5) == 0
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait()
        os.close(fd)
