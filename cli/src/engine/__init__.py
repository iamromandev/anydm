import functools
import importlib.util
import threading
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Any, Protocol
from urllib.parse import urlsplit

from src.core.errors import DownloadError, UsageError
from src.core.progress import Reporter


class Kind(StrEnum):
    HTTP = "http"
    MEDIA = "media"
    TORRENT = "torrent"


@dataclass(frozen=True)
class Job:
    source: str
    dest_dir: Path
    connections: int
    format: str | None
    playlist: bool
    seed: bool
    insecure: bool
    cookies: Path | None = None  # media only: a checked Netscape cookies file; HTTP and torrent engines never read it


class Engine(Protocol):
    async def run(self, job: Job, report: Reporter) -> list[Path]: ...


@functools.cache
def _media_extractors() -> tuple[Any, ...]:
    from yt_dlp.extractor import gen_extractor_classes  # lazy: yt_dlp is slow to import

    return tuple(ie for ie in gen_extractor_classes() if ie.ie_key() != "Generic")


def _local_media_supported(url: str) -> bool:
    """True when a yt-dlp extractor other than the generic one handles the URL."""
    if importlib.util.find_spec("yt_dlp") is None:
        return False
    return any(ie.suitable(url) for ie in _media_extractors())


def _has_local_yt_dlp() -> bool:
    return importlib.util.find_spec("yt_dlp") is not None


@functools.cache
def _docker_runner() -> Any:
    from src.engine.docker import DockerRunner  # lazy: keeps --help fast

    return DockerRunner.from_settings()


MAX_NOTICES = 20  # a long-running TUI that never takes them must not collect them without end
_notices: list[str] = []
_notices_lock = threading.Lock()  # classification runs on worker threads in the TUI


def _add_notice(note: str) -> None:
    with _notices_lock:
        if note not in _notices and len(_notices) < MAX_NOTICES:
            _notices.append(note)


def take_notices() -> list[str]:
    """Notes classification left for the user (media detection skipped, an image pulled); each is returned once."""
    with _notices_lock:
        taken = list(_notices)
        _notices.clear()
    return taken


def _media_supported(url: str) -> bool:
    """Media check in-process when yt-dlp is installed here, else in a throwaway container. Without Docker, False.
    A media image that speaks another protocol is an error (DownloadError), not a reason to try HTTP."""
    if _has_local_yt_dlp():
        return _local_media_supported(url)
    from src.engine.docker import DockerUnavailable  # lazy: keeps --help fast

    try:
        return _docker_runner().supported(url, _add_notice)
    except DockerUnavailable as e:
        _add_notice(f"media detection skipped: {e} (see anydm doctor)")
        return False


def _media_cached(url: str) -> bool:
    """`_media_supported` from what is already known: never runs docker. A link nobody asked about is not media."""
    if _has_local_yt_dlp():
        return _local_media_supported(url)
    return _docker_runner().cached_supported(url) is True


def _is_http(source: str) -> bool:
    try:
        parts = urlsplit(source)
        return parts.scheme in ("http", "https") and bool(parts.hostname)
    except ValueError:
        return False


def resolve(source: str, forced: Kind | None, *, cached_only: bool = False) -> Kind:
    """What kind of download `source` is. With `cached_only`, a media check that was not asked before is not run
    (so docker is never started): for callers on an event loop that already resolved the link on a thread."""
    if forced is Kind.HTTP and not _is_http(source):
        raise UsageError(f"{source!r} is not an http(s) URL with a host")
    if forced is not None:
        return forced
    if source.lower().startswith("magnet:"):
        return Kind.TORRENT
    if source.endswith(".torrent") and Path(source).is_file():
        return Kind.TORRENT
    if _is_http(source):
        if urlsplit(source).path.endswith(".torrent"):
            return Kind.TORRENT
        if (_media_cached if cached_only else _media_supported)(source):
            return Kind.MEDIA
        return Kind.HTTP
    raise UsageError(f"cannot tell what to do with {source!r}: expected a magnet, .torrent file or http(s) URL")


def engine_for(kind: Kind) -> Engine:
    if kind is Kind.HTTP:
        from src.engine.http.engine import HttpEngine  # lazy: keeps --help fast

        return HttpEngine()
    if kind is Kind.MEDIA:
        if _has_local_yt_dlp():
            from src.engine.media import MediaEngine  # lazy: keeps --help fast

            return MediaEngine()
        from src.engine.media_docker import DockerMediaEngine  # lazy: keeps --help fast

        runner = _docker_runner()
        if not runner.available():
            raise DownloadError("media downloads need Docker; see anydm doctor")
        return DockerMediaEngine(runner)
    from src.engine.torrent import TorrentEngine  # lazy: keeps --help fast

    return TorrentEngine()
