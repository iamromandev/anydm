"""yt-dlp adapter. The only module in this project that imports yt-dlp.

Three things earn the wrapper. yt-dlp is synchronous and does blocking HTTP, so
every call goes through ``asyncio.to_thread``, under a ceiling. Its failures
arrive as one loosely typed ``DownloadError`` with a human-readable message,
which ``classify`` turns into this project's ``Error``, including the
``retry_able`` flag the worker reads. And its info dicts become the small,
site-neutral shapes the rest of the code reads: ``SiteInfo`` and ``Format``.

Which formats to use is not decided here; ``format.py`` does that. Everything
the site offers comes back, storyboards and HLS included.

YouTube's JavaScript challenges are solved by Deno when it is on PATH (the
image ships it). Without it yt-dlp falls back to a client that needs no
JavaScript, which it has deprecated and which offers fewer formats.
"""

from __future__ import annotations

import asyncio
import errno
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from functools import lru_cache
from importlib import metadata
from pathlib import Path
from typing import Any, Protocol

from loguru import logger

from src.core.error import Error
from src.lib.site import error as site_error
from src.lib.site.format import Format, is_fragmented

#: A blocking ``url -> info dict`` call; yt-dlp's in production, a fake in tests.
Extract = Callable[[str], dict[str, Any]]

#: Runs yt-dlp's documented ``download()`` for one page with the given params.
#: yt-dlp's in production, a fake in tests.
Download = Callable[[dict[str, Any], str], None]

_UNSUPPORTED_MARKERS = ("unsupported url", "is not a valid url")
_MISSING_MARKERS = ("private", "unavailable", "removed", "deleted", "does not exist", "http error 404")
_FORBIDDEN_MARKERS = (
    "age restricted",
    "age-restricted",
    "confirm your age",
    "members-only",
    "members only",
    "login required",
    "in your country",
)


@dataclass(frozen=True, slots=True)
class SiteInfo:
    #: yt-dlp's name for the site's extractor: "Youtube", "Vimeo", ...
    extractor: str
    #: The site's own id for the media.
    id: str
    title: str
    uploader: str = ""
    duration: int = 0
    thumbnail: str = ""
    webpage_url: str = ""
    is_live: bool = False
    formats: list[Format] = field(default_factory=list)


@dataclass(frozen=True, slots=True)
class Resolved:
    """A fetchable URL for one format, and the headers its server expects."""

    url: str
    headers: dict[str, str] = field(default_factory=dict)
    #: A playlist of fragments (HLS, DASH...), which only yt-dlp's downloader
    #: fetches. The worker routes the part by this.
    fragmented: bool = False


@dataclass(frozen=True, slots=True)
class FormatProgress:
    """How far a ``download_format`` has got, in yt-dlp-free terms."""

    downloaded_bytes: int
    #: Exact on the last call. yt-dlp's fragment-based estimate before it.
    total_bytes: int | None
    speed_bps: int | None
    eta_seconds: int | None


class DownloadStopped(Exception):
    """Raised through yt-dlp by the progress hook when the caller asked to stop."""


class SiteClient(Protocol):
    async def extract(self, url: str) -> SiteInfo:
        """What the page at ``url`` offers."""
        ...

    async def resolve(self, url: str, format_ids: Sequence[str]) -> dict[str, Resolved]:
        """Fresh URLs for ``format_ids``, from one extraction.

        Never cached: stream URLs expire within hours and bind to the IP that
        asked, so a resumed download asks again.
        """
        ...

    async def open(self, url: str) -> tuple[SiteInfo, dict[str, Resolved]]:
        """``extract`` and a ``Resolved`` for every format, from one extraction.

        For the player, which chooses and fetches in the same request and
        would otherwise wait on two extractions of seconds each.
        """
        ...

    def download_format(
        self,
        page_url: str,
        format_id: str,
        destination: Path,
        *,
        concurrency: int,
        rate_bps: int,
        on_progress: Callable[[FormatProgress], None],
        should_stop: Callable[[], bool],
    ) -> None:
        """Download one format of ``page_url`` into ``destination``, with yt-dlp's own downloader.

        Blocking, so callers run it in a thread. For the parts only yt-dlp can
        fetch: HLS, DASH and the rest of the fragmented protocols. It resumes
        from what an earlier call left beside ``destination``, and raises
        ``DownloadStopped`` once ``should_stop`` says so.
        """
        ...


def classify(exc: Exception) -> Error:
    """A yt-dlp failure as an ``Error``, with the retry decision attached.

    yt-dlp raises one ``DownloadError`` for everything, so the message is all
    there is to go on. YouTube's bot check ("Sign in to confirm you're not a
    bot") deliberately matches nothing: it clears with time, so it lands in the
    retryable default.
    """
    text = str(exc).lower()
    if any(marker in text for marker in _UNSUPPORTED_MARKERS):
        return site_error.unsupported_url(str(exc))
    if any(marker in text for marker in _MISSING_MARKERS):
        return site_error.media_unavailable(str(exc))
    if any(marker in text for marker in _FORBIDDEN_MARKERS):
        return site_error.media_forbidden(str(exc))
    return site_error.extraction_failed(str(exc))


def _raw_formats(info: dict[str, Any]) -> list[dict[str, Any]]:
    """The format dicts, or the info dict itself for a page with just one."""
    formats = info.get("formats")
    if formats:
        return list(formats)
    return [info] if info.get("url") else []


def _to_site_info(url: str, info: dict[str, Any]) -> SiteInfo:
    return SiteInfo(
        extractor=str(info.get("extractor_key") or info.get("extractor") or ""),
        id=str(info.get("id") or ""),
        title=info.get("title") or "",
        uploader=info.get("uploader") or info.get("channel") or "",
        duration=int(info.get("duration") or 0),
        thumbnail=info.get("thumbnail") or "",
        webpage_url=info.get("webpage_url") or url,
        is_live=bool(info.get("is_live")),
        formats=[Format.from_ytdlp(raw) for raw in _raw_formats(info)],
    )


def _resolved(info: dict[str, Any]) -> dict[str, Resolved]:
    """Every format's URL, headers and kind, by format id."""
    return {
        str(raw.get("format_id")): Resolved(
            str(raw["url"]),
            dict(raw.get("http_headers") or {}),
            fragmented=is_fragmented(raw.get("protocol")),
        )
        for raw in _raw_formats(info)
        if raw.get("url")
    }


def _progress(status: dict[str, Any]) -> FormatProgress:
    """A yt-dlp progress-hook dict as a ``FormatProgress``."""
    total = status.get("total_bytes") or status.get("total_bytes_estimate")
    downloaded = status.get("downloaded_bytes")
    if downloaded is None and status.get("status") == "finished":
        # The file was already whole on disk from an earlier attempt, and
        # yt-dlp reports only its size.
        downloaded = total
    downloaded = int(downloaded or 0)
    speed = status.get("speed")
    eta = status.get("eta")
    return FormatProgress(
        downloaded_bytes=downloaded,
        # An estimate can trail what has already arrived, and a total below the
        # bytes on disk would put the percentage past 100.
        total_bytes=max(int(total), downloaded) if total else None,
        speed_bps=int(speed) if speed else None,
        eta_seconds=int(eta) if eta is not None else None,
    )


def _chain(exc: BaseException) -> list[BaseException]:
    """``exc`` and everything it wraps: causes, contexts, and yt-dlp's ``exc_info``."""
    found: list[BaseException] = []
    pending = [exc]
    while pending:
        current = pending.pop()
        if any(current is seen for seen in found):
            continue
        found.append(current)
        info = getattr(current, "exc_info", None)
        wrapped = info[1] if isinstance(info, tuple) and len(info) > 1 else None
        pending.extend(e for e in (current.__cause__, current.__context__, wrapped) if isinstance(e, BaseException))
    return found


class _Log:
    """Routes yt-dlp's own messages into loguru; its chatter stays out."""

    def debug(self, message: str) -> None:
        return None

    def info(self, message: str) -> None:
        return None

    def warning(self, message: str) -> None:
        logger.warning("yt-dlp|{}", message)

    def error(self, message: str) -> None:
        logger.error("yt-dlp|{}", message)


def _ytdlp_extract(url: str) -> dict[str, Any]:
    # Imported here so the module loads, and its tests run, without touching
    # yt-dlp's extractor registry until a real extraction is asked for.
    import yt_dlp

    options = {
        "quiet": True,
        "skip_download": True,
        # A watch link that also names a playlist means the one video.
        "noplaylist": True,
        "socket_timeout": 20,
        "logger": _Log(),
    }
    with yt_dlp.YoutubeDL(options) as ydl:
        return ydl.extract_info(url, download=False)


def _ytdlp_download(params: dict[str, Any], url: str) -> None:
    import yt_dlp

    with yt_dlp.YoutubeDL(params) as ydl:
        ydl.download([url])


class YtDlpClient(SiteClient):
    def __init__(
        self,
        extract: Extract = _ytdlp_extract,
        *,
        download: Download = _ytdlp_download,
        timeout_s: float = 60.0,
    ) -> None:
        self._extract = extract
        self._download = download
        self._timeout_s = timeout_s

    async def extract(self, url: str) -> SiteInfo:
        return _to_site_info(url, await self._info(url))

    async def resolve(self, url: str, format_ids: Sequence[str]) -> dict[str, Resolved]:
        offered = _resolved(await self._info(url))
        missing = [format_id for format_id in format_ids if format_id not in offered]
        if missing:
            raise site_error.no_format_for_preset(missing[0])
        return {format_id: offered[format_id] for format_id in format_ids}

    async def open(self, url: str) -> tuple[SiteInfo, dict[str, Resolved]]:
        info = await self._info(url)
        return _to_site_info(url, info), _resolved(info)

    def download_format(
        self,
        page_url: str,
        format_id: str,
        destination: Path,
        *,
        concurrency: int,
        rate_bps: int,
        on_progress: Callable[[FormatProgress], None],
        should_stop: Callable[[], bool],
    ) -> None:
        started = False

        def hook(status: dict[str, Any]) -> None:
            # yt-dlp may call this from several fragment threads at once. The
            # flag only ever goes from False to True.
            nonlocal started
            started = True
            on_progress(_progress(status))
            if should_stop():
                raise DownloadStopped

        params: dict[str, Any] = {
            "format": format_id,
            "outtmpl": str(destination),
            "noplaylist": True,
            # A later attempt picks up from yt-dlp's own record of the
            # fragments already on disk (``<destination>.ytdl``).
            "continuedl": True,
            # yt-dlp only downloads. Muxing and remuxing are the post-processor's.
            "fixup": "never",
            "postprocessors": [],
            # A fragment that cannot be had fails the download. Skipping it
            # would leave a gap nobody is told about.
            "skip_unavailable_fragments": False,
            # yt-dlp's own fragment downloaders, never an ffmpeg subprocess:
            # the hooks, stopping and resuming were all probed on these.
            "external_downloader": {"m3u8": "native", "dash": "native"},
            "concurrent_fragment_downloads": concurrency,
            "progress_hooks": [hook],
            "quiet": True,
            "noprogress": True,
            "socket_timeout": 20,
            "logger": _Log(),
        }
        if rate_bps > 0:
            params["ratelimit"] = rate_bps

        if should_stop():
            raise DownloadStopped
        try:
            self._download(params, page_url)
        except Exception as exc:
            chain = _chain(exc)
            stopped = next((e for e in chain if isinstance(e, DownloadStopped)), None)
            if stopped is not None:
                raise stopped from None
            full = next((e for e in chain if isinstance(e, OSError) and e.errno == errno.ENOSPC), None)
            if full is not None:
                raise full from None
            if not started:
                # Nothing downloaded yet, so this is yt-dlp reading the page.
                logger.error("YtDlpClient|{} {}: {}", page_url, format_id, exc)
                raise classify(exc) from exc
            # Mid-download: expired fragment URLs answer 403 or 404, and the
            # next attempt extracts fresh ones.
            logger.error("YtDlpClient|{} {} failed mid-download: {}", page_url, format_id, exc)
            raise site_error.transfer_failed(str(exc)) from exc

    async def _info(self, url: str) -> dict[str, Any]:
        try:
            # The ceiling abandons the wait, not the thread: yt-dlp cannot be
            # interrupted, and its own socket timeout bounds what it leaves behind.
            info = await asyncio.wait_for(asyncio.to_thread(self._extract, url), timeout=self._timeout_s)
        except TimeoutError as exc:
            logger.error("YtDlpClient|{} took longer than {}s", url, self._timeout_s)
            raise site_error.extraction_failed(f"timed out after {self._timeout_s:g}s") from exc
        except Error:
            raise
        except Exception as exc:
            logger.error("YtDlpClient|{}: {}", url, exc)
            raise classify(exc) from exc
        if info.get("_type") == "playlist":
            raise site_error.playlist_not_supported()
        # yt-dlp's generic extractor "extracts" any file link, a PDF included,
        # as one format of unknown codecs. That is a direct download, not a
        # page on a site, and saying so lets the add box fall back to one.
        if info.get("direct"):
            raise site_error.unsupported_url(f"{url} is a file, not a page on a site")
        return info


@lru_cache
def get_site_client() -> SiteClient:
    return YtDlpClient()


def ytdlp_version() -> str:
    """The installed yt-dlp's version, or ``""`` when it is not installed.

    Read from the package's metadata: importing any part of yt-dlp loads the
    whole package, and ``GET /settings`` asks for this every time it opens.
    """
    try:
        return metadata.version("yt-dlp")
    except metadata.PackageNotFoundError:
        return ""
