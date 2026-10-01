"""What a source is, and what every source shares: one result type, one error, magnets, and asking over HTTP."""

from dataclasses import dataclass
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from typing import Protocol
from urllib.parse import quote

import httpx

#: Public trackers added to a magnet built from a bare info hash.
TRACKERS = (
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://tracker.dler.org:6969/announce",
    "udp://open.dstud.io:6969/announce",
    "udp://exodus.desync.com:6969/announce",
)


@dataclass(frozen=True)
class Result:
    title: str
    size: int | None
    seeders: int | None
    leechers: int | None
    published: datetime | None
    category: str
    info_hash: str | None
    magnet: str | None
    link: str | None
    indexers: tuple[str, ...]
    copy_from: str = ""


class SourceError(Exception):
    """A source's answer that holds no results, in words fit to show."""

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class Source(Protocol):
    """Somewhere to look for torrents. ``q == ""`` means browse the latest."""

    @property
    def name(self) -> str: ...

    def supports(self, q: str, category: str) -> bool:
        """Whether this source can answer the request at all; if not, it is skipped, not failed."""
        ...

    async def fetch(self, client: httpx.AsyncClient, q: str, category: str, timeout_s: float) -> list[Result]:
        """Ask and parse. Raises ``SourceError`` for an answer it can't use; httpx errors pass through."""
        ...


def magnet_for(info_hash: str, name: str) -> str:
    trackers = "".join(f"&tr={quote(tracker, safe='')}" for tracker in TRACKERS)
    return f"magnet:?xt=urn:btih:{info_hash.lower()}&dn={quote(name, safe='')}{trackers}"


def to_count(value: str | int | float | None) -> int | None:
    """A non-negative whole number from text or a number; anything else is unknown."""
    if isinstance(value, bool) or value in (None, ""):
        return None
    try:
        count = int(float(value))
    except (TypeError, ValueError):
        return None
    return count if count >= 0 else None


def to_date(value: str | None) -> datetime | None:
    """An RFC 2822 date, always zone-aware: one with no zone ("-0000") is UTC."""
    if not value:
        return None
    try:
        parsed = parsedate_to_datetime(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


async def get(client: httpx.AsyncClient, url: str, timeout_s: float, refused: str = "the indexer refused the key") -> httpx.Response:
    """GET ``url``; a refusal or an error status becomes a ``SourceError``, a timeout or network failure passes through."""
    response = await client.get(url, timeout=timeout_s)
    if response.status_code in (401, 403):
        raise SourceError(refused)
    if response.status_code >= 400:
        raise SourceError(f"answered {response.status_code}")
    return response
