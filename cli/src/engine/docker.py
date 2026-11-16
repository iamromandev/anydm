"""Talks to the `docker` command (never through a shell) for media downloads in throwaway containers."""

import asyncio
import contextlib
import functools
import json
import os
import subprocess
import threading
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from src.core.errors import DownloadError
from src.core.names import clean_display
from src.core.progress import Reporter

PROTOCOL = 1
LABEL = "anydm"  # label key; the value is "media"
# Who started a container: a random token for this process, plus its pid and start time (the pair that tells a live
# process from a dead one whose pid was reused). Not the hostname: that changes with the network on a laptop.
TOKEN_LABEL = "anydm.token"
PID_LABEL = "anydm.pid"
START_LABEL = "anydm.started"
MOUNT = "/downloads"  # where the output folder appears inside the container
CHECK_TIMEOUT = 60.0  # a classification container: start, import yt-dlp, answer
PULL_TIMEOUT = 600.0  # a first-run pull inside a classification
CHECK_LIFETIME = 50  # seconds; the container's own watchdog ends a check before the host gives up (see media_runner)
RECHECK = 30.0  # a "Docker is not available" answer is asked again after this many seconds
STDERR_TAIL = 2048
PROCESS_TOKEN = uuid.uuid4().hex
_clock = time.monotonic  # a name of its own so tests can move time


class DockerUnavailable(DownloadError):
    """Docker, or the media image, cannot be used."""


def local_ref(image: str) -> bool:
    """True when the reference names no registry (no '/' before the first ':'), so it can only be built locally."""
    return "/" not in image.split(":", 1)[0]


def tail(text: str) -> str:
    return clean_display(text.strip()[-STDERR_TAIL:])


async def in_thread[T](fn: Callable[..., T], *args: Any) -> T:
    """`fn(*args)` on a daemon thread. Unlike asyncio.to_thread, a call still blocked on a docker command after its
    task was cancelled does not hold up the end of the loop or of the interpreter."""
    loop = asyncio.get_running_loop()
    fut: asyncio.Future[T] = loop.create_future()

    def deliver(result: T | None, exc: BaseException | None) -> None:
        if fut.done():
            return
        if exc is not None:
            fut.set_exception(exc)
        else:
            fut.set_result(result)  # ty: ignore[invalid-argument-type]

    def work() -> None:
        try:
            result, exc = fn(*args), None
        except BaseException as e:
            result, exc = None, e
        with contextlib.suppress(RuntimeError):  # the loop is already closed
            loop.call_soon_threadsafe(deliver, result, exc)

    threading.Thread(target=work, daemon=True, name="anydm-docker").start()
    return await fut


def process_start(pid: int) -> str:
    """An opaque string that is the same for the whole life of process `pid` and differs for a later process that
    reuses the pid; "" when it cannot be read."""
    try:
        fields = Path(f"/proc/{pid}/stat").read_text().rpartition(")")[2].split()
        return fields[19]  # starttime, in clock ticks since boot
    except (OSError, IndexError):
        pass
    try:  # macOS and others
        out = subprocess.run(
            ["ps", "-o", "lstart=", "-p", str(pid)], capture_output=True, text=True, timeout=3,
            stdin=subprocess.DEVNULL, env={**os.environ, "LC_ALL": "C"},
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return " ".join(out.stdout.split())


@functools.cache
def own_start() -> str:
    return process_start(os.getpid())


@dataclass(frozen=True)
class DockerRunner:
    docker: str
    image: str
    _cache: dict[str, Any] = field(default_factory=dict, compare=False, repr=False)

    @classmethod
    def from_settings(cls) -> DockerRunner:
        from src.config.settings import get_settings  # lazy: keeps --help fast

        s = get_settings()
        return cls(s.docker, s.media_image)

    def _run(self, *args: str, timeout: float) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [self.docker, *args], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL
        )

    def available(self, timeout: float = 3.0) -> bool:
        """True when the docker command exists and its daemon answers within `timeout`. A yes is kept; a no is
        asked again after RECHECK seconds, so starting Docker later is noticed by a long-running TUI."""
        known = self._cache.get("available")
        if known is not None and (known[0] or _clock() - known[1] < RECHECK):
            return known[0]
        try:
            ok = self._run("version", "--format", "{{.Server.Version}}", timeout=timeout).returncode == 0
        except (OSError, subprocess.SubprocessError):
            ok = False
        self._cache["available"] = (ok, _clock())
        return ok

    def image_present(self) -> bool:
        try:
            return self._run("image", "inspect", self.image, timeout=10).returncode == 0
        except (OSError, subprocess.SubprocessError):
            return False

    def _missing_local(self) -> DownloadError:
        return DownloadError(f"media image {self.image} not found; run `make build-media` or set ANYDM_MEDIA_IMAGE")

    async def ensure_image(self, report: Reporter) -> None:
        if await in_thread(self.image_present):
            return
        if local_ref(self.image):
            raise self._missing_local()
        report.notice(f"pulling {self.image} (first run)")
        proc = await asyncio.create_subprocess_exec(
            self.docker, "pull", "--", self.image,
            stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE,
        )
        try:
            _, err = await proc.communicate()
        except asyncio.CancelledError:
            with contextlib.suppress(ProcessLookupError):
                proc.kill()
            raise
        if proc.returncode != 0:
            raise DownloadError(f"cannot pull {self.image}: {tail(err.decode(errors='replace'))}")

    def cached_supported(self, url: str) -> bool | None:
        """The answer `supported` already gave for `url` (False when it failed), or None when it was never asked.
        Never runs docker: safe on an event loop."""
        answer = self._cache.get("supported", {}).get(url)
        return None if answer is None else answer is True

    def supported(self, url: str, notice: Callable[[str], None] | None = None) -> bool:
        """Ask a throwaway container whether a yt-dlp extractor other than the generic one handles `url`.

        Answers, and failures other than "no Docker", are remembered per URL for the life of the runner. A first
        run that has to pull the image says so through `notice`. Raises DockerUnavailable when Docker or the image
        cannot be used (callers treat the link as plain HTTP), and a plain DownloadError when the image speaks
        another protocol (an error the user must fix)."""
        answers: dict[str, bool | DownloadError] = self._cache.setdefault("supported", {})
        known = answers.get(url)
        if isinstance(known, DownloadError):
            raise type(known)(str(known))
        if known is not None:
            return known
        try:
            value = self._ask(url, notice)
        except DockerUnavailable as e:
            if self.available():  # "no Docker" is cheap to ask again: available() remembers it
                answers[url] = e
            raise
        except DownloadError as e:
            answers[url] = e
            raise
        answers[url] = value
        return value

    def _ask(self, url: str, notice: Callable[[str], None] | None) -> bool:
        if not self.available():
            raise DockerUnavailable("Docker is not available")
        self.reap_once()
        if not self._cache.get("image") and not self.image_present():
            if local_ref(self.image):
                raise DockerUnavailable(str(self._missing_local()))
            if notice is not None:
                notice(f"pulling {self.image} (first run)")
            try:
                pulled = self._run("pull", "--", self.image, timeout=PULL_TIMEOUT)
            except (OSError, subprocess.SubprocessError) as e:
                raise DockerUnavailable(f"cannot pull {self.image}: {type(e).__name__}") from e
            if pulled.returncode != 0:
                raise DockerUnavailable(f"cannot pull {self.image}: {tail(pulled.stderr)}")
        self._cache["image"] = True  # one `image inspect` per process: every check would pay for it otherwise
        args = self.base_args(None)
        cmd = [*args, self.image, "check", "--protocol", str(PROTOCOL), f"--url={url}"]
        try:
            done = self._run(*cmd, timeout=CHECK_TIMEOUT)
        except subprocess.TimeoutExpired as e:
            self._remove(args[args.index("--name") + 1])  # subprocess.run killed the client, not the container
            raise DockerUnavailable("media check timed out") from e
        except (OSError, subprocess.SubprocessError) as e:
            raise DockerUnavailable(f"media check failed: {type(e).__name__}") from e
        hello: object = None
        value: bool | None = None
        for line in done.stdout.splitlines():
            try:
                obj = json.loads(line)
            except ValueError:
                continue
            if isinstance(obj, dict) and obj.get("type") == "hello":
                hello = obj.get("protocol")
            if isinstance(obj, dict) and obj.get("type") == "supported" and isinstance(obj.get("value"), bool):
                value = obj["value"]
        if hello is not None and hello != PROTOCOL:
            raise DownloadError("update the media image: it speaks a different protocol than this anydm")
        if value is None:
            raise DockerUnavailable(f"media check failed: {tail(done.stderr) or 'no answer from the image'}")
        if hello is None:
            raise DownloadError("update the media image: it did not say hello")
        return value

    def _remove(self, name: str) -> None:
        with contextlib.suppress(OSError, subprocess.SubprocessError):
            self._run("rm", "-f", name, timeout=10)

    def base_args(self, dest: Path | None) -> list[str]:
        """`docker run` options for one throwaway container: the caller's uid and gid, a minimal environment, and
        at most one mount (the output folder). The last element is `--`, so the image that the caller appends
        is never read as an option; the image's own arguments follow it."""
        uid = os.getuid()
        if uid == 0:
            # The container would run as the host's root with a host folder mounted, and write root-owned files.
            raise DockerUnavailable("media containers are not started as root (uid 0); run anydm as a normal user")
        args = [
            "run", "--rm",
            "--label", f"{LABEL}=media",
            "--label", f"{TOKEN_LABEL}={PROCESS_TOKEN}",
            "--label", f"{PID_LABEL}={os.getpid()}",
            "--label", f"{START_LABEL}={own_start()}",
            "--user", f"{uid}:{os.getgid()}",
            "-e", "HOME=/tmp",
            "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges",
            "--network", "default",
            "--name", f"anydm-media-{uuid.uuid4().hex[:12]}",
        ]
        if dest is not None:
            if ":" in str(dest):
                raise DownloadError(f"cannot mount {dest} into Docker: the folder name contains ':'")
            args += ["-v", f"{dest}:{MOUNT}"]
        return [*args, "--"]

    def reap_once(self) -> None:
        """`reap_stale`, the first time it is asked in this runner's life."""
        if not self._cache.get("reaped"):
            self._cache["reaped"] = True
            self.reap_stale()

    def reap_stale(self) -> None:
        """Remove containers that earlier anydm runs left behind: the process that started one is gone, or its pid
        now belongs to another process (a different start time). Containers of this process, and of any anydm that
        still runs, are left alone. Assumes the Docker daemon is on this machine, as the pids are."""
        if not self.available():
            return
        fmt = "\t".join(["{{.ID}}", *(f'{{{{.Label "{k}"}}}}' for k in (PID_LABEL, START_LABEL, TOKEN_LABEL))])
        try:
            listed = self._run("ps", "-a", "--filter", f"label={LABEL}=media", "--format", fmt, timeout=10)
        except (OSError, subprocess.SubprocessError):
            return
        for line in listed.stdout.splitlines():
            cid, pid, started, token = ([*line.split("\t"), "", "", ""])[:4]
            if not cid or not pid.isdigit() or token == PROCESS_TOKEN or _owner_running(int(pid), started):
                continue
            self._remove(cid)


def _owner_running(pid: int, started: str) -> bool:
    """True unless process `pid` is gone, or is a later process that reused the pid."""
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        pass
    now = process_start(pid)
    return not (started and now and now != started)
