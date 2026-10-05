"""What a download is, read off the rows it was added from.

``Download`` keeps its state and nothing about what it is: the address is its
primary mirror's source, the site its provider, the title its media's or its
torrent's or its first file's. ``describe`` puts those back together from a row
loaded with ``RELATED``, so every reader derives them the same way.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Any
from urllib.parse import urlsplit

from src.data.type import MediaKind, Platform, SourceKind
from src.lib.identity import site_ref, url_ref

#: How each kind of source fetches its bytes.
_PLATFORM = {
    SourceKind.CONTENT: Platform.SITE,
    SourceKind.DIRECT: Platform.DIRECT,
    SourceKind.TORRENT: Platform.TORRENT,
}


@dataclass(frozen=True, slots=True)
class Described:
    source_url: str
    platform: Platform
    #: The site (yt-dlp's extractor key, e.g. ``Youtube``), ``http`` or ``torrent``.
    provider: str
    #: The provider's own id: the site's id for the page, an info hash, or an address hash.
    ref: str
    title: str
    media_kind: MediaKind
    #: The ``Media`` row of a site download; ``None`` for a torrent or a direct file.
    media: Any
    info_hash: str | None


def primary_mirror(row: Any) -> Any | None:
    """The mirror tried first: lowest ``priority``, then the oldest."""
    mirrors = list(row.mirrors)
    return min(mirrors, key=lambda mirror: (mirror.priority, mirror.created_at)) if mirrors else None


def _name_of(url: str) -> str:
    return PurePosixPath(urlsplit(url).path).name


def describe(row: Any, files: Any = ()) -> Described:
    """``row`` loaded with ``RELATED``; ``files`` its files, when the caller has them, for a direct file's name."""
    media = row.media
    mirror = primary_mirror(row)
    source = mirror.source if mirror is not None else None
    url = source.url.value if source is not None else ""
    platform = _PLATFORM[source.kind] if source is not None else (Platform.SITE if media else Platform.DIRECT)
    provider = source.provider.name if source is not None else ""
    torrents = list(source.torrents) if source is not None else []
    info_hash = torrents[0].info_hash if torrents else None

    if platform == Platform.TORRENT:
        ref = info_hash or ""
    elif platform == Platform.SITE:
        ref = site_ref(provider, url) if url else ""
    else:
        ref = url_ref(url) if url else ""

    title = (
        (media.title if media else "")
        or (torrents[0].name if torrents else "")
        or next((file.filename for file in files), "")
        or _name_of(url)
    )
    return Described(
        source_url=url,
        platform=platform,
        provider=provider,
        ref=ref,
        title=title,
        media_kind=media.kind if media else MediaKind.FILE,
        media=media,
        info_hash=info_hash,
    )
