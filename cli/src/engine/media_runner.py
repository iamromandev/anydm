"""Entrypoint of the anydm media image: `python -m src.engine.media_runner check|download ...`.

Runs inside the container, never on the host (it needs yt-dlp). Prints one JSON object per line on stdout for
DockerMediaEngine to read; the reports of the existing MediaEngine become those lines."""

import argparse
import asyncio
import json
import os
import sys
import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import TextIO

from src import engine
from src.core.cookies import copy_private
from src.core.errors import DownloadError, PartialDownload
from src.core.progress import Progress
from src.engine import Job
from src.engine.media import MediaEngine

PROTOCOL = 2
DOWNLOADS = Path("/downloads")  # where the host mounts the output folder
COOKIES_MOUNT = Path("/run/anydm/cookies.txt")  # where the host mounts the cookies file, read-only (docker.COOKIES_MOUNT)
PROGRESS_INTERVAL = 0.1  # seconds between progress lines
CHECK_LIFETIME = 50  # seconds a `check` may live; the host waits longer (docker.CHECK_TIMEOUT) and then removes it


class JsonReporter:
    """A Reporter that prints JSON lines. Called from yt-dlp's thread, so writes are serialised."""

    def __init__(self, out: TextIO) -> None:
        self._out = out
        self._lock = threading.Lock()
        self._last = 0.0

    def emit(self, **line: object) -> None:
        with self._lock:
            self._out.write(json.dumps(line) + "\n")
            self._out.flush()

    def update(self, p: Progress) -> None:
        now = time.monotonic()
        with self._lock:
            if now - self._last < PROGRESS_INTERVAL:
                return
            self._last = now
        self.emit(type="progress", done=p.done, total=p.total, speed=p.speed, name=p.name)

    def notice(self, msg: str) -> None:
        self.emit(type="notice", message=msg)


def start_watchdog(seconds: float, on_expire: Callable[[], None] | None = None) -> None:
    """End this process after `seconds` (a daemon timer): a check container must not outlive a host that died."""
    timer = threading.Timer(seconds, on_expire or (lambda: os._exit(1)))
    timer.daemon = True
    timer.start()


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="media_runner")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("check", "download"):
        cmd = sub.add_parser(name)
        cmd.add_argument("--protocol", type=int, required=True)
        cmd.add_argument("--url", required=True)
        if name == "download":
            cmd.add_argument("--format")
            cmd.add_argument("--playlist", action="store_true")
            cmd.add_argument("--insecure", action="store_true")
            cmd.add_argument("--cookies", action="store_true")  # the file is at COOKIES_MOUNT
    return parser


def run(argv: list[str], out: TextIO) -> int:
    reporter = JsonReporter(out)
    reporter.emit(type="hello", protocol=PROTOCOL)
    try:
        args = _parser().parse_args(argv)
    except SystemExit as e:
        return int(e.code) if isinstance(e.code, int) else 2
    if args.protocol != PROTOCOL:
        print(f"unsupported protocol {args.protocol}", file=sys.stderr)
        return 2
    if args.command == "check":
        reporter.emit(type="supported", value=engine._local_media_supported(args.url))
        return 0
    copy: Path | None = None
    try:
        if args.cookies:
            # a copy of the read-only mount in this container's own /tmp, never in the output folder
            if not COOKIES_MOUNT.is_file():
                raise DownloadError("cookies mount missing")
            try:
                copy = copy_private(COOKIES_MOUNT)
            except OSError as e:
                raise DownloadError(f"cannot read the cookies mount: {e.strerror or type(e).__name__}") from e
        job = Job(args.url, DOWNLOADS, 1, args.format, args.playlist, False, args.insecure, copy)
        paths = asyncio.run(MediaEngine(in_image=True).run(job, reporter))
    except KeyboardInterrupt:
        return 130
    except PartialDownload as e:
        reporter.emit(type="error", message=str(e), partial=[str(p) for p in e.paths])
        return 1
    except DownloadError as e:
        reporter.emit(type="error", message=str(e), partial=[])
        return 1
    except Exception as e:  # never print a traceback: it can carry credentials
        reporter.emit(type="error", message=f"internal error: {type(e).__name__}", partial=[])
        return 1
    finally:
        if copy is not None:
            copy.unlink(missing_ok=True)
    reporter.emit(type="done", paths=[str(p) for p in paths])
    return 0


def main() -> None:
    argv = sys.argv[1:]
    if argv[:1] == ["check"]:  # here, not in run(): the watchdog ends the process, which a test calling run() must not get
        start_watchdog(CHECK_LIFETIME)
    sys.exit(run(argv, sys.stdout))


if __name__ == "__main__":
    main()
