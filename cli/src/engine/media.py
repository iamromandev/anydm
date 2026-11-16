import asyncio
import atexit
import contextlib
import re
import threading
from pathlib import Path
from typing import Any

from src.core.cookies import LOGIN_HINT_EXPIRED, LOGIN_HINT_NO_COOKIES, copy_private
from src.core.errors import DownloadError, PartialDownload
from src.core.names import clean_display
from src.core.paths import inside
from src.core.progress import Progress, Reporter
from src.engine import Job

OUTTMPL = "%(title)s [%(id)s].%(ext)s"
SOCKET_TIMEOUT = 20
CANCEL_WAIT = 0.75  # how long a cancelled run() waits for yt-dlp's thread to stop: Ctrl-C must stay prompt
RESTART_WAIT = 10.0  # how long a new run waits for the previous thread of the same job to stop

_live: dict[tuple[str, Path], threading.Thread] = {}  # jobs whose yt-dlp thread is still running
_live_lock = threading.Lock()
# yt-dlp's wordings for "this needs an account": whole phrases, so a URL like /login.mp4 or a word like
# "cookies" in another error does not look like one
_LOGIN = re.compile(
    r"\b(?:sign in to confirm|(?:log ?in|sign ?in) (?:is )?required|please (?:log|sign) ?in|private video"
    r"|members[- ]only|age[- ]restricted|authentication)\b",
    re.IGNORECASE,
)
_copies: set[Path] = set()  # private cookie copies that exist right now


def _forget_copies() -> None:
    """At exit: a yt-dlp thread that was still blocked then never reached its `finally`."""
    for copy in list(_copies):
        copy.unlink(missing_ok=True)


atexit.register(_forget_copies)


def _collect(info: dict[str, Any]) -> list[str]:
    found = [d["filepath"] for d in info.get("requested_downloads") or [] if d.get("filepath")]
    for entry in info.get("entries") or []:
        if entry:
            found.extend(_collect(entry))
    return found


def _complete_length(e: Exception) -> int | None:
    """N from the `Content-Range: bytes */N` of the HTTP 416 that a yt-dlp error wraps, else None."""
    cause = (getattr(e, "exc_info", None) or (None, None, None))[1]
    if getattr(cause, "status", None) != 416:
        return None
    headers = getattr(getattr(cause, "response", None), "headers", None)
    found = re.fullmatch(r"bytes \*/(\d+)", str(headers.get("Content-Range") or "")) if headers else None
    return int(found.group(1)) if found else None


def _adopt_complete_part(e: Exception, targets: list[str]) -> bool:
    """A run stopped after the last byte but before yt-dlp renamed the .part file leaves it complete, and a server
    answers the resume with 416 (yt-dlp then fails instead of finishing). When the 416 says the size is exactly that
    of a target's .part file, make it the file and report True so the download is run again (it finds the file)."""
    length = _complete_length(e)
    if length is None:
        return False
    for target in targets:
        part, final = Path(f"{target}.part"), Path(target)
        try:
            if part.is_file() and part.stat().st_size == length and not final.exists():
                part.rename(final)
                return True
        except OSError:
            continue
    return False


def _map_error(e: Exception, in_image: bool = False, cookies: Path | None = None) -> DownloadError:
    """`cookies` is the cookies file the job used, if any. Inside the image it is the container's private copy, so
    it is not named."""
    from yt_dlp.cookies import CookieLoadError

    msg = str(e).removeprefix("ERROR: ")
    if isinstance(e, CookieLoadError):  # its own text is just "failed to load cookies"
        where = "" if in_image or cookies is None else f": {clean_display(str(cookies))}"
        return DownloadError(f"cookies file could not be parsed{where}")
    if ("ffmpeg" in msg.lower() or "ffprobe" in msg.lower()) and not in_image:  # the image always has ffmpeg
        return DownloadError(f"{msg}; ffmpeg is needed to merge formats, run `anydm doctor` to check your setup")
    if _LOGIN.search(msg):
        return DownloadError(f"{msg}; {LOGIN_HINT_EXPIRED if cookies is not None else LOGIN_HINT_NO_COOKIES}")
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
    def __init__(self, *, in_image: bool = False) -> None:
        self._in_image = in_image  # inside the media image: ffmpeg is always there, so no `anydm doctor` hint

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
        targets: list[str] = []  # where each video is saved, for _adopt_complete_part

        def match_filter(info: dict[str, Any], *, incomplete: bool = False) -> str | None:
            if stop.is_set():
                raise DownloadCancelled
            if not incomplete and ydl_ref and info.get("_type", "video") == "video":
                target = ydl_ref[0].prepare_filename(info)
                inside(job.dest_dir, target)  # before anything is written
                targets.append(target)
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
        copy: Path | None = None
        if job.cookies is not None:
            # yt-dlp saves its cookie jar back to `cookiefile` on close, so it gets a copy: the user's file is never written
            try:
                copy = copy_private(job.cookies)
            except OSError as e:
                raise DownloadError(f"cannot read the cookies file: {e.strerror or type(e).__name__}") from e
            _copies.add(copy)
            opts["cookiefile"] = str(copy)
        try:
            for attempt in (1, 2):
                try:
                    with yt_dlp.YoutubeDL(opts) as ydl:
                        ydl_ref[:] = [ydl]
                        info = ydl.extract_info(job.source, download=True)
                    break
                except DownloadCancelled:
                    raise KeyboardInterrupt from None
                except DownloadError:
                    raise
                except YoutubeDLError as e:
                    if attempt == 1 and _adopt_complete_part(e, targets):
                        continue
                    raise _map_error(e, self._in_image, job.cookies) from e
                except OSError as e:
                    raise DownloadError(str(e.strerror or e)) from e
        finally:
            if copy is not None:
                copy.unlink(missing_ok=True)
                _copies.discard(copy)
        if not info:
            raise DownloadError("yt-dlp returned no information for this URL")
        paths: list[Path] = []
        for raw in _collect(info):
            p = inside(job.dest_dir, raw)
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
