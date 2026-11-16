import functools
import importlib.util
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Any, Protocol
from urllib.parse import urlsplit

from src.core.errors import UsageError
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


class Engine(Protocol):
    async def run(self, job: Job, report: Reporter) -> list[Path]: ...


@functools.cache
def _media_extractors() -> tuple[Any, ...]:
    from yt_dlp.extractor import gen_extractor_classes  # lazy: yt_dlp is slow to import

    return tuple(ie for ie in gen_extractor_classes() if ie.ie_key() != "Generic")


def _media_supported(url: str) -> bool:
    """True when a yt-dlp extractor other than the generic one handles the URL."""
    if importlib.util.find_spec("yt_dlp") is None:
        return False
    return any(ie.suitable(url) for ie in _media_extractors())


def _is_http(source: str) -> bool:
    try:
        parts = urlsplit(source)
        return parts.scheme in ("http", "https") and bool(parts.hostname)
    except ValueError:
        return False


def resolve(source: str, forced: Kind | None) -> Kind:
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
        if _media_supported(source):
            return Kind.MEDIA
        return Kind.HTTP
    raise UsageError(f"cannot tell what to do with {source!r}: expected a magnet, .torrent file or http(s) URL")


def engine_for(kind: Kind) -> Engine:
    if kind is Kind.HTTP:
        from src.engine.http.engine import HttpEngine  # lazy: keeps --help fast

        return HttpEngine()
    if kind is Kind.MEDIA:
        from src.engine.media import MediaEngine  # lazy: keeps --help fast

        return MediaEngine()
    from src.engine.torrent import TorrentEngine  # lazy: keeps --help fast

    return TorrentEngine()
