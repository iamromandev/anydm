"""Downloads and lookups in a throwaway plugin container. The built-in media plugin is src.engine.media_runner."""

import asyncio
import contextlib
import subprocess
import threading
import time
from collections.abc import Callable
from pathlib import Path, PurePosixPath
from typing import Any

from src.core.errors import DownloadError, PartialDownload
from src.core.names import clean_display
from src.core.paths import inside
from src.core.progress import Progress, Reporter
from src.engine import Job
from src.plugin.contract import MAX_LINE, PROTOCOL, BadJson, Info, LineReader, LineTooLong, clean_text, parse_info
from src.plugin.model import Plugin, media_image_plugin
from src.plugin.runner import MOUNT, PluginRunner, in_thread, tail

INFO_TIMEOUT = 120.0  # an `info` container: start, look the link up, answer
STOP_WAIT = 1.5  # R9: the container is stopped and removed within 2 s of a cancel; this is all of that budget
STOP_RM_RESERVE = 0.5  # of STOP_WAIT, kept for `rm -f` when the container does not stop after the kill
STOP_PAUSE = 0.05  # between tries of `rm -f` for a container that does not exist yet
STOP_SYNC_WAIT = 1.0  # a cancel that arrives while stopping: the removal is finished synchronously, for this long
STDERR_KEEP = 64 * 1024

_SHARING_HINT = "; is the output folder shared with Docker? (Docker Desktop: Settings > Resources > File sharing)"


def _host_path(dest: Path, raw: object) -> Path:
    """A path the container reported (under /downloads) as a path inside `dest` on the host."""
    text = raw if isinstance(raw, str) else ""
    posix = PurePosixPath(text)
    mount = PurePosixPath(MOUNT)
    if not posix.is_absolute() or not posix.is_relative_to(mount) or posix == mount:
        raise DownloadError(f"refusing to use {clean_display(text)!r}: it is outside the output folder")
    try:
        return inside(dest, str(PurePosixPath(*posix.relative_to(mount).parts)))
    except (ValueError, RuntimeError) as e:  # a NUL byte, or a symlink loop
        raise DownloadError(f"refusing to use {clean_display(text)!r}: it is not a usable path") from e


def _host_paths(dest: Path, raw: object, who: str = "the media container") -> list[Path]:
    """The `paths` or `partial` list of a result line: a list of strings, each mapped by `_host_path`."""
    if raw is None:
        return []
    if not isinstance(raw, list) or not all(isinstance(p, str) for p in raw):
        raise DownloadError(f"{who} reported its files in a form this anydm does not understand")
    return [_host_path(dest, p) for p in raw]


class _Notes:
    """A Reporter that passes notices on (the image pull) and ignores progress."""

    def __init__(self, notice: Callable[[str], None] | None) -> None:
        self._notice = notice

    def update(self, p: Progress) -> None:
        pass

    def notice(self, msg: str) -> None:
        if self._notice is not None:
            self._notice(msg)


class PluginEngine:
    def __init__(self, runner: PluginRunner, plugin: Plugin | None = None) -> None:
        self._runner = runner
        self._plugin = plugin or media_image_plugin(runner.image)
        # the media image says "update the media image"; another plugin is named
        self._who = "the media container" if self._plugin.builtin else f"plugin {self._plugin.name}"

    def _update(self, why: str) -> DownloadError:
        if self._plugin.builtin:
            return DownloadError(f"update the media image: {why}")
        return DownloadError(f"plugin {self._plugin.name}: {why}")

    async def info(self, url: str, cookies: Path | None = None, *, fmt: str | None = None, playlist: bool = False,
                   notice: Callable[[str], None] | None = None) -> Info:
        """Ask the plugin what `url` is, without downloading it. Raises DownloadError for a plugin that fails or
        has no `info`."""
        runner, plugin = self._runner, self._plugin
        if "info" not in plugin.capabilities:
            raise DownloadError(f"{self._who} cannot show information")
        if not await in_thread(runner.available):
            raise DownloadError("plugin lookups need Docker; see anydm doctor")
        await runner.ensure_image(_Notes(notice), plugin.image)
        await in_thread(runner.reap_once)
        extra = ((f"--format={fmt}",) if fmt else ()) + (("--playlist",) if playlist else ())
        cancel, finished = threading.Event(), threading.Event()

        def work() -> list[dict[str, Any]]:
            try:
                return runner.call(plugin, "info", url, INFO_TIMEOUT, cookies=cookies, extra=extra, cancel=cancel)
            finally:
                finished.set()

        try:
            lines = await in_thread(work)
        except asyncio.CancelledError:
            cancel.set()  # the call kills its client and removes the container
            finished.wait(STOP_SYNC_WAIT)  # blocking on purpose: the loop may close right after this
            raise
        for line in lines:
            if line["type"] == "error":
                raise DownloadError(clean_text(line.get("message")) or f"{self._who} failed")
            if line["type"] == "info":
                return parse_info(line)
        raise DownloadError(f"{self._who} returned no information")

    async def run(self, job: Job, report: Reporter) -> list[Path]:
        runner = self._runner
        plugin = self._plugin
        if not await in_thread(runner.available):
            raise DownloadError("media downloads need Docker; see anydm doctor")
        await runner.ensure_image(report, plugin.image)
        await in_thread(runner.reap_once)
        args = runner.plugin_args(plugin, "download", job.dest_dir, job.cookies)
        name = args[args.index("--name") + 1]
        # --url=X and --format=X, never "--url X": a value that starts with "-" must not be read as an option.
        cmd = [*args, plugin.image, "download", "--protocol", str(PROTOCOL), f"--url={job.source}"]
        if job.format:
            cmd.append(f"--format={job.format}")
        if job.playlist:
            cmd.append("--playlist")
        if job.insecure:
            cmd.append("--insecure")
        if job.cookies is not None and "cookies" in plugin.permissions:
            cmd.append("--cookies")  # the file itself is mounted, never passed on the command line
        proc = await asyncio.create_subprocess_exec(
            runner.docker, *cmd,
            stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            limit=MAX_LINE,
        )
        stderr = bytearray()
        drain = asyncio.create_task(self._drain(proc, stderr))
        try:
            outcome = await self._read(proc, report)
            await proc.wait()
            await drain
        finally:
            drain.cancel()
            if proc.returncode is None:
                await self._stopped(name, proc)
        return self._result(outcome, job.dest_dir, proc.returncode or 0, bytes(stderr))

    @staticmethod
    async def _drain(proc: asyncio.subprocess.Process, sink: bytearray) -> None:
        assert proc.stderr is not None
        while chunk := await proc.stderr.read(4096):
            sink += chunk
            del sink[:-STDERR_KEEP]

    async def _read(self, proc: asyncio.subprocess.Process, report: Reporter) -> dict[str, Any]:
        """Feed the container's JSON lines to `report`; returns the `done` or `error` line (or {})."""
        assert proc.stdout is not None
        reader = LineReader()
        hello = False
        outcome: dict[str, Any] = {}
        while True:
            try:
                raw = await proc.stdout.readline()
            except ValueError:  # a line over MAX_LINE: dropped
                reader.drop()
                continue
            if not raw:
                break
            try:
                line = reader.feed(raw)
            except (LineTooLong, BadJson):
                continue
            if line is None:
                continue
            kind = line["type"]
            if kind == "hello":
                if line.get("protocol") != PROTOCOL:
                    raise self._update("it speaks a different protocol than this anydm")
                hello = True
                continue
            if not hello and kind in ("progress", "notice", "done", "error"):
                raise self._update("it did not say hello")
            if kind == "progress":
                self._progress(line, report)
            elif kind == "notice" and isinstance(line.get("message"), str):
                report.notice(clean_text(line["message"]))
            elif kind in ("done", "error"):
                outcome = dict(line)
        outcome["_hello"] = hello
        return outcome

    @staticmethod
    def _progress(line: dict[str, Any], report: Reporter) -> None:
        done, total, speed, name = line.get("done"), line.get("total"), line.get("speed"), line.get("name")
        if not isinstance(done, int | float) or isinstance(done, bool):
            return
        if total is not None and (not isinstance(total, int | float) or isinstance(total, bool)):
            return
        report.update(Progress(int(done), int(total) if total is not None else None,
                               float(speed) if isinstance(speed, int | float) else 0.0,
                               name=clean_text(name) or None))

    def _result(self, outcome: dict[str, Any], dest: Path, code: int, stderr: bytes) -> list[Path]:
        text = tail(stderr.decode(errors="replace"))
        kind = outcome.get("type")
        if kind not in ("done", "error"):
            if code == 0:
                raise (self._update("it finished without a result") if not outcome.get("_hello")
                       else DownloadError(f"{self._who} returned no result"))
            hint = _SHARING_HINT if "mount" in text.lower() or "not shared" in text.lower() else ""
            raise DownloadError(f"{self._who} exited with {code}: {text or 'no output'}{hint}")
        if kind == "done":
            paths = _host_paths(dest, outcome.get("paths"), self._who)
            if not paths:
                raise DownloadError("yt-dlp finished but reported no downloaded files" if self._plugin.builtin
                                    else f"plugin {self._plugin.name} saved nothing")
            return paths
        message = clean_text(str(outcome.get("message") or f"{self._who} failed"))
        partial = _host_paths(dest, outcome.get("partial"), self._who)
        if partial:
            raise PartialDownload(message, partial)
        raise DownloadError(message)

    async def _stopped(self, name: str, proc: asyncio.subprocess.Process) -> None:
        """`_stop` that a second cancel (another Ctrl-C, the loop closing) cannot cut short: it runs as a task of its
        own that this awaits through a shield, and when this is cancelled anyway the removal is finished here,
        synchronously."""
        task = asyncio.ensure_future(self._stop(name, proc))
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            self._remove_now(name)
            with contextlib.suppress(ProcessLookupError):
                proc.kill()
            raise

    def _remove_now(self, name: str) -> None:
        """`docker rm -f` without the event loop, for up to STOP_SYNC_WAIT (the container may not exist yet)."""
        deadline = time.monotonic() + STOP_SYNC_WAIT
        while (left := deadline - time.monotonic()) > 0:
            with contextlib.suppress(OSError, subprocess.SubprocessError):
                done = subprocess.run(
                    [self._runner.docker, "rm", "-f", name], capture_output=True, timeout=left,
                    stdin=subprocess.DEVNULL,
                )
                if done.returncode == 0:
                    return
            time.sleep(STOP_PAUSE)

    async def _stop(self, name: str, proc: asyncio.subprocess.Process) -> None:
        """Cancel: stop the container and remove it, all within STOP_WAIT.

        `kill` can fail because the container does not exist yet (docker run was only just started): then go
        straight to `rm -f`, and keep trying while the `docker run` client lives. After the container is gone the
        client is killed, not waited for."""
        docker = self._runner.docker
        loop = asyncio.get_running_loop()
        deadline = loop.time() + STOP_WAIT

        def left() -> float:
            return max(0.0, deadline - loop.time())

        if await self._quick(docker, "kill", "--signal", "INT", name, timeout=min(0.4, left())):
            # yt-dlp gets the interrupt and stops by itself, keeping the partial file
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(proc.wait(), max(0.0, left() - STOP_RM_RESERVE))
        while left() > 0:  # at least one `rm -f`, even when the client already exited: --rm may still be at work
            if await self._quick(docker, "rm", "-f", name, timeout=left()) or proc.returncode is not None:
                break
            await asyncio.sleep(min(STOP_PAUSE, left()))
        if proc.returncode is None:
            with contextlib.suppress(ProcessLookupError):
                proc.kill()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(proc.wait(), left())

    @staticmethod
    async def _quick(*cmd: str, timeout: float) -> bool:
        """Run a short command; True when it exits 0 within `timeout`."""
        try:
            p = await asyncio.create_subprocess_exec(
                *cmd, stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
        except OSError:
            return False
        try:
            return await asyncio.wait_for(p.wait(), timeout) == 0
        except TimeoutError:
            with contextlib.suppress(ProcessLookupError):
                p.kill()
            return False


DockerMediaEngine = PluginEngine  # the name the media code and its tests used
