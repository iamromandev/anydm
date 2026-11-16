import asyncio
import contextlib
import threading
from pathlib import Path
from typing import Any

from src.core.errors import DownloadError, PartialDownload
from src.core.progress import Progress, Reporter
from src.engine import Job

OUTTMPL = "%(title)s [%(id)s].%(ext)s"
SOCKET_TIMEOUT = 20
CANCEL_WAIT = 0.75  # how long a cancelled run() waits for yt-dlp's thread to stop: Ctrl-C must stay prompt
RESTART_WAIT = 10.0  # how long a new run waits for the previous thread of the same job to stop

_live: dict[tuple[str, Path], threading.Thread] = {}  # jobs whose yt-dlp thread is still running
_live_lock = threading.Lock()


def _collect(info: dict[str, Any]) -> list[str]:
    found = [d["filepath"] for d in info.get("requested_downloads") or [] if d.get("filepath")]
    for entry in info.get("entries") or []:
        if entry:
            found.extend(_collect(entry))
    return found


def _inside(dest: Path, raw: str) -> Path:
    base = dest.resolve()
    path = Path(raw)
    resolved = (path if path.is_absolute() else base / path).resolve()
    if not resolved.is_relative_to(base):
        raise DownloadError(f"refusing to use {raw!r}: it is outside the output folder")
    return base / resolved.relative_to(base)


def _map_error(e: Exception) -> DownloadError:
    msg = str(e).removeprefix("ERROR: ")
    if "ffmpeg" in msg.lower() or "ffprobe" in msg.lower():
        return DownloadError(f"{msg}; ffmpeg is needed to merge formats, run `anydm doctor` to check your setup")
    return DownloadError(msg)


class _Logger:
    """Drops yt-dlp's own output (get.py prints our errors); warnings become notices."""

    def __init__(self, report: Reporter) -> None:
        self._report = report
        self.errors: list[str] = []

    def debug(self, msg: str) -> None:
        pass

    def info(self, msg: str) -> None:
        pass

    def warning(self, msg: str) -> None:
        self._report.notice(str(msg).removeprefix("WARNING: "))

    def error(self, msg: str) -> None:
        self.errors.append(str(msg).removeprefix("ERROR: "))


class MediaEngine:
    async def run(self, job: Job, report: Reporter) -> list[Path]:
        # A daemon thread that is not joined: a blocked yt-dlp (extraction, ffmpeg, a stalled socket)
        # must not hold up Ctrl-C the way the default executor does at loop shutdown.
        loop = asyncio.get_running_loop()
        key = (job.source, job.dest_dir)
        await self._previous_run_stopped(key)
        fut: asyncio.Future[list[Path]] = loop.create_future()
        ended = asyncio.Event()
        stop = threading.Event()

        def deliver(result: list[Path] | None, exc: BaseException | None) -> None:
            ended.set()
            if fut.done():
                return
            if exc is not None:
                fut.set_exception(exc)
            else:
                fut.set_result(result or [])

        def work() -> None:
            try:
                result, exc = self._download(job, report, stop), None
            except BaseException as e:
                result, exc = None, e
            with _live_lock:
                _live.pop(key, None)
            with contextlib.suppress(RuntimeError):  # loop already closed after a cancel
                loop.call_soon_threadsafe(deliver, result, exc)

        thread = threading.Thread(target=work, daemon=True, name="anydm-media")
        with _live_lock:
            _live[key] = thread
        thread.start()
        try:
            return await fut
        except asyncio.CancelledError:
            stop.set()
            # Give the thread a moment to see `stop`, so a retry does not race it on the same .part file.
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(ended.wait(), CANCEL_WAIT)
            raise

    @staticmethod
    async def _previous_run_stopped(key: tuple[str, Path]) -> None:
        """A cancelled run can leave its thread alive past CANCEL_WAIT; never start a second one beside it."""
        with _live_lock:
            old = _live.get(key)
        if old is None or not old.is_alive():
            return
        deadline = asyncio.get_running_loop().time() + RESTART_WAIT
        while old.is_alive():
            if asyncio.get_running_loop().time() > deadline:
                raise DownloadError("the previous attempt is still stopping; try again in a moment")
            await asyncio.sleep(0.05)

    def _download(self, job: Job, report: Reporter, stop: threading.Event) -> list[Path]:
        import yt_dlp  # lazy: keeps --help fast
        from yt_dlp.utils import DownloadCancelled, YoutubeDLError

        def hook(d: dict[str, Any]) -> None:
            if stop.is_set():
                raise DownloadCancelled
            if d.get("status") != "downloading":
                return
            try:
                total = d.get("total_bytes") or d.get("total_bytes_estimate")
                name = Path(d["filename"]).name if d.get("filename") else None
                report.update(Progress(d.get("downloaded_bytes") or 0, total, float(d.get("speed") or 0), name=name))
            except Exception:
                pass

        logger = _Logger(report)
        ydl_ref: list[Any] = []

        def match_filter(info: dict[str, Any], *, incomplete: bool = False) -> str | None:
            if stop.is_set():
                raise DownloadCancelled
            if not incomplete and ydl_ref and info.get("_type", "video") == "video":
                _inside(job.dest_dir, ydl_ref[0].prepare_filename(info))  # before anything is written
            return None

        opts: dict[str, Any] = {
            "paths": {"home": str(job.dest_dir)},
            "outtmpl": OUTTMPL,
            "noplaylist": not job.playlist,
            "quiet": True,
            "noprogress": True,
            "continuedl": True,
            "nocheckcertificate": job.insecure,
            "progress_hooks": [hook],
            "socket_timeout": SOCKET_TIMEOUT,
            # Windows-safe names: no ':' '?' '*' etc., keeps unicode (restrictfilenames would force ASCII)
            "windowsfilenames": True,
            "logger": logger,
            "match_filter": match_filter,
        }
        if not job.playlist:
            opts["playlist_items"] = "1"  # noplaylist does not stop a pure playlist URL
        else:
            opts["ignoreerrors"] = "only_download"
        if job.format:
            opts["format"] = job.format
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                ydl_ref.append(ydl)
                info = ydl.extract_info(job.source, download=True)
        except DownloadCancelled:
            raise KeyboardInterrupt from None
        except DownloadError:
            raise
        except YoutubeDLError as e:
            raise _map_error(e) from e
        except OSError as e:
            raise DownloadError(str(e.strerror or e)) from e
        if not info:
            raise DownloadError("yt-dlp returned no information for this URL")
        paths: list[Path] = []
        for raw in _collect(info):
            p = _inside(job.dest_dir, raw)
            if p not in paths:
                paths.append(p)
        failures = list(logger.errors) if job.playlist else []
        if failures:
            # a failed entry still carries its target path in the info; keep only what is on disk
            paths = [p for p in paths if p.exists()]
        missing = [i for i, e in enumerate(info.get("entries") or [], 1) if not e] if job.playlist else []
        if not failures:
            failures = [f"playlist entry {i} failed to download" for i in missing]
        for msg in failures:
            report.notice(msg)
        if failures:
            raise PartialDownload(f"{len(failures)} playlist entries failed", paths)
        if not paths:
            raise DownloadError("yt-dlp finished but reported no downloaded files")
        return paths
