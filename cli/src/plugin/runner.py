"""Talks to the `docker` command (never through a shell) to run plugins in throwaway containers."""

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
from src.plugin.contract import MAX_LINE, PROTOCOL, BadJson, LineReader, LineTooLong
from src.plugin.model import Plugin, media_image_plugin

LABEL = "anydm"  # label key; the value is "media" (the built-in plugin) or "plugin"
PLUGIN_LABEL = "anydm.plugin"  # the plugin's name
LABEL_VALUES = ("media", "plugin")
# Who started a container: a random token for this process, plus its pid and start time (the pair that tells a live
# process from a dead one whose pid was reused). Not the hostname: that changes with the network on a laptop.
TOKEN_LABEL = "anydm.token"
PID_LABEL = "anydm.pid"
START_LABEL = "anydm.started"
MOUNT = "/downloads"  # where the output folder appears inside the container
COOKIES_MOUNT = "/run/anydm/cookies.txt"  # where the cookies file appears there (read-only), outside MOUNT
CHECK_TIMEOUT = 60.0  # a classification container: start, import yt-dlp, answer
PULL_TIMEOUT = 600.0  # a first-run pull inside a classification
CHECK_LIFETIME = 50  # seconds; the container's own watchdog ends a check before the host gives up (see media_runner)
RECHECK = 30.0  # a "Docker is not available" answer is asked again after this many seconds
STDERR_TAIL = 2048
CALL_TIMEOUT = 30.0  # a `manifest` or `info` container, unless the caller says otherwise
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
class PluginRunner:
    docker: str
    image: str  # the built-in media plugin's image
    _cache: dict[str, Any] = field(default_factory=dict, compare=False, repr=False)

    @classmethod
    def from_settings(cls) -> PluginRunner:
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

    def image_present(self, image: str | None = None) -> bool:
        try:
            return self._run("image", "inspect", image or self.image, timeout=10).returncode == 0
        except (OSError, subprocess.SubprocessError):
            return False

    def digest_of(self, ref: str) -> str | None:
        """What to run for the image `ref` so that it cannot change later: its `repo@sha256:...` digest (the first
        one whose repository is `ref`'s), or the image id for one that was only built here. None when the image is
        not there."""
        try:
            done = self._run("image", "inspect", "--format", "{{json .RepoDigests}} {{.Id}}", "--", ref, timeout=10)
        except (OSError, subprocess.SubprocessError):
            return None
        if done.returncode != 0:
            return None
        listed, _, image_id = done.stdout.strip().rpartition(" ")
        try:
            digests = json.loads(listed)
        except ValueError:
            digests = []
        repo = ref.split("@", 1)[0].rsplit(":", 1)[0] if ":" in ref.split("/")[-1] else ref.split("@", 1)[0]
        for d in digests if isinstance(digests, list) else []:
            if isinstance(d, str) and d.partition("@")[0] == repo:
                return d
        return image_id or None

    def _missing_local(self, image: str | None = None) -> DownloadError:
        image = image or self.image
        if image != self.image:
            return DownloadError(f"plugin image {image} not found")
        return DownloadError(f"media image {image} not found; run `make build-media` or set ANYDM_MEDIA_IMAGE")

    async def ensure_image(self, report: Reporter, image: str | None = None) -> None:
        """Make sure `image` (default: the media image) is here, pulling it when it names a registry."""
        image = image or self.image
        if await in_thread(self.image_present, image):
            return
        if local_ref(image):
            raise self._missing_local(image)
        report.notice(f"pulling {image} (first run)")
        proc = await asyncio.create_subprocess_exec(
            self.docker, "pull", "--", image,
            stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE,
        )
        try:
            _, err = await proc.communicate()
        except asyncio.CancelledError:
            with contextlib.suppress(ProcessLookupError):
                proc.kill()
            raise
        if proc.returncode != 0:
            raise DownloadError(f"cannot pull {image}: {tail(err.decode(errors='replace'))}")

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

    def base_args(self, dest: Path | None, cookies: Path | None = None) -> list[str]:
        """`plugin_args` for the built-in media plugin and a `download` (no mount for a missing `dest`)."""
        return self.plugin_args(media_image_plugin(self.image), "download", dest, cookies)

    def plugin_args(self, plugin: Plugin, cmd: str, dest: Path | None, cookies: Path | None) -> list[str]:
        """`docker run` options for one throwaway container of `plugin` running `cmd`: the caller's uid and gid, a
        minimal environment, no network unless the plugin's `network` permission is approved, and at most two
        mounts: the output folder (`download` only) and the cookies file, read-only at COOKIES_MOUNT (`download` and
        `info`, and only for a plugin with the `cookies` permission). The last element is `--`, so the image that
        the caller appends is never read as an option; the image's own arguments follow it."""
        uid = os.getuid()
        if uid == 0:
            # The container would run as the host's root with a host folder mounted, and write root-owned files.
            raise DockerUnavailable("plugin containers are not started as root (uid 0); run anydm as a normal user")
        args = [
            "run", "--rm",
            "--label", f"{LABEL}={'media' if plugin.builtin else 'plugin'}",
            "--label", f"{PLUGIN_LABEL}={plugin.name}",
            "--label", f"{TOKEN_LABEL}={PROCESS_TOKEN}",
            "--label", f"{PID_LABEL}={os.getpid()}",
            "--label", f"{START_LABEL}={own_start()}",
            "--user", f"{uid}:{os.getgid()}",
            "-e", "HOME=/tmp",
            "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges",
            # manifest and check never get the network: media's `check` only matches URL patterns
            "--network", "default" if "network" in plugin.permissions and cmd not in ("manifest", "check") else "none",
            "--name", f"anydm-{plugin.name}-{uuid.uuid4().hex[:12]}",
        ]
        if dest is not None and cmd == "download":
            if ":" in str(dest):
                raise DownloadError(f"cannot mount {dest} into Docker: the folder name contains ':'")
            args += ["-v", f"{dest}:{MOUNT}"]
        if cookies is not None and cmd in ("download", "info") and "cookies" in plugin.permissions:
            # --mount, not -v: a ':' in the path is fine, but ',' separates its options (and would let a name add some)
            if any(c in str(cookies) for c in ',"') or any(c < " " or c == "\x7f" for c in str(cookies)):
                raise DownloadError(
                    f"cannot mount the cookies file {clean_display(str(cookies))} into Docker: "
                    "its path contains ',' or '\"' or a control character"
                )
            args += ["--mount", f"type=bind,src={cookies},dst={COOKIES_MOUNT},readonly"]
        return [*args, "--"]

    def call(
        self, plugin: Plugin, cmd: str, url: str | None, timeout: float = CALL_TIMEOUT,
        *, cookies: Path | None = None, extra: tuple[str, ...] = (), cancel: threading.Event | None = None,
    ) -> list[dict[str, Any]]:
        """Run one short command of a plugin (`manifest`, `check`, `info`) and return the lines it printed after
        `hello`. Output is read as it arrives and bounded (line length, line count, a stderr tail). The container
        is removed when it takes longer than `timeout`, when `cancel` is set, when a limit is hit, or when this is
        interrupted. Raises DownloadError for a plugin that speaks another protocol, breaks the contract, fails or
        times out."""
        args = self.plugin_args(plugin, cmd, None, cookies)
        name = args[args.index("--name") + 1]
        argv = [*args, plugin.image, cmd, "--protocol", str(PROTOCOL)]
        if url is not None:
            argv.append(f"--url={url}")  # one argument: a URL that starts with '-' is never an option
        argv += extra
        if cookies is not None and cmd == "info" and "cookies" in plugin.permissions:
            argv.append("--cookies")  # the file itself is mounted, never passed on the command line
        try:
            proc = subprocess.Popen(
                [self.docker, *argv], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE
            )
        except (OSError, subprocess.SubprocessError) as e:
            raise DownloadError(f"plugin {plugin.name} failed: {type(e).__name__}") from e
        who = "the media image" if plugin.builtin else f"plugin {plugin.name}"
        finished = threading.Event()
        expired = threading.Event()

        def watch() -> None:
            deadline = _clock() + timeout
            while not finished.wait(0.05):
                if _clock() >= deadline or (cancel is not None and cancel.is_set()):
                    expired.set()
                    with contextlib.suppress(OSError):
                        proc.kill()
                    return

        err = bytearray()

        def drain() -> None:
            assert proc.stderr is not None
            while chunk := proc.stderr.read(4096):
                err.extend(chunk)
                del err[:-STDERR_TAIL]

        watcher = threading.Thread(target=watch, daemon=True, name="anydm-call-watch")
        drainer = threading.Thread(target=drain, daemon=True, name="anydm-call-stderr")
        watcher.start()
        drainer.start()
        ok = False
        try:
            lines, hello = self._read_call(proc, plugin, who)
            proc.wait()
            drainer.join(5)
            if expired.is_set():
                raise DownloadError("cancelled" if cancel is not None and cancel.is_set() else f"plugin {plugin.name} timed out")
            ok = True
        finally:
            finished.set()
            if not ok:
                with contextlib.suppress(OSError):
                    proc.kill()
                self._remove(name)  # killing the client does not stop the container
            for pipe in (proc.stdout, proc.stderr):
                if pipe is not None:
                    pipe.close()
            proc.wait()
        stderr = bytes(err)
        if not hello:
            if proc.returncode != 0:
                text = tail(stderr.decode(errors="replace"))
                raise DownloadError(f"{who} exited with {proc.returncode}: {text or 'no output'}")
            raise DownloadError("update the media image: it did not say hello" if plugin.builtin
                                else f"{who} did not say hello")
        if proc.returncode != 0 and not any(line["type"] == "error" for line in lines):
            text = tail(stderr.decode(errors="replace"))
            raise DownloadError(f"{who} exited with {proc.returncode}: {text or 'no output'}")
        return lines

    @staticmethod
    def _read_call(proc: subprocess.Popen[bytes], plugin: Plugin, who: str) -> tuple[list[dict[str, Any]], bool]:
        """The lines after `hello` of a running `call`, and whether `hello` came. Reads at most MAX_LINE bytes of
        a line at a time, and skips the rest of a longer one."""
        assert proc.stdout is not None
        reader, lines, hello = LineReader(), [], False
        while raw := proc.stdout.readline(MAX_LINE + 1):
            if len(raw) > MAX_LINE and not raw.endswith(b"\n"):
                while (rest := proc.stdout.readline(MAX_LINE + 1)) and not rest.endswith(b"\n"):
                    pass
            try:
                obj = reader.feed(raw.removesuffix(b"\n"))
            except (LineTooLong, BadJson):
                continue
            if obj is None:
                continue
            if obj["type"] == "hello":
                if obj.get("protocol") != PROTOCOL:
                    raise DownloadError(
                        "update the media image: it speaks a different protocol than this anydm"
                        if plugin.builtin else f"{who} speaks a different protocol than this anydm"
                    )
                hello = True
            elif not hello:
                raise DownloadError("update the media image: it did not say hello" if plugin.builtin
                                    else f"{who} did not say hello")
            else:
                lines.append(obj)
        return lines, hello

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
        # One `ps` for the key alone (docker ANDs label filters, so "media or plugin" cannot be asked in one
        # filter); the label's value comes back in the last column and is checked here.
        labels = (PID_LABEL, START_LABEL, TOKEN_LABEL, LABEL)
        fmt = "\t".join(["{{.ID}}", *(f'{{{{.Label "{k}"}}}}' for k in labels)])
        try:
            listed = self._run("ps", "-a", "--filter", f"label={LABEL}", "--format", fmt, timeout=10)
        except (OSError, subprocess.SubprocessError):
            return
        for line in listed.stdout.splitlines():
            cid, pid, started, token, value = ([*line.split("\t"), "", "", "", ""])[:5]
            if not cid or not pid.isdigit() or token == PROCESS_TOKEN or value not in LABEL_VALUES:
                continue
            if _owner_running(int(pid), started):
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


DockerRunner = PluginRunner  # the name the media code and its tests used
