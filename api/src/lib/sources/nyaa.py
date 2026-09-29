"""Nyaa's RSS feed: search, and its latest list (spec: built-in sources)."""

import re
from dataclasses import dataclass
from urllib.parse import urlencode
from xml.etree import ElementTree

import httpx

from src.lib.sources.source import Result, SourceError, get, magnet_for, to_count, to_date

NAME = "nyaa"
DEFAULT_URL = "https://nyaa.si"

_NS = "{https://nyaa.si/xmlns/nyaa}"
_HASH = re.compile(r"[0-9a-f]{40}")
#: The app's categories that Nyaa has a code for; anything else is skipped.
_CODES = {"all": "0_0", "tv": "1_0", "music": "2_0", "software": "6_0", "books": "3_0"}
#: Nyaa's top-level category (the digit before the underscore) as the app's category.
_GROUPS = {"1": "tv", "2": "music", "3": "books", "6": "software"}
_UNITS = {"bytes": 1, "b": 1, "kib": 1024, "mib": 1024**2, "gib": 1024**3, "tib": 1024**4}


def _size(text: str | None) -> int | None:
    found = re.fullmatch(r"\s*([\d.]+)\s*([A-Za-z]+)\s*", text or "")
    if not found or found[2].lower() not in _UNITS:
        return None
    try:
        return int(float(found[1]) * _UNITS[found[2].lower()])
    except ValueError:
        return None


def parse(body: bytes | str, category: str = "all") -> list[Result]:
    """Nyaa's RSS; items without a title or a valid info hash are dropped."""
    try:
        root = ElementTree.fromstring(body)
    except ElementTree.ParseError as error:
        raise SourceError("unreadable answer") from error
    results: list[Result] = []
    for item in root.iter("item"):
        title = (item.findtext("title") or "").strip()
        info_hash = (item.findtext(f"{_NS}infoHash") or "").strip().lower()
        if not title or not _HASH.fullmatch(info_hash):
            continue
        group = (item.findtext(f"{_NS}categoryId") or "")[:1]
        results.append(
            Result(
                title=title,
                size=_size(item.findtext(f"{_NS}size")),
                seeders=to_count(item.findtext(f"{_NS}seeders")),
                leechers=to_count(item.findtext(f"{_NS}leechers")),
                published=to_date(item.findtext("pubDate")),
                category=_GROUPS.get(group, "other"),
                info_hash=info_hash,
                magnet=magnet_for(info_hash, title),
                link=None,
                indexers=(NAME,),
            )
        )
    return results


@dataclass(frozen=True)
class Nyaa:
    base: str = DEFAULT_URL
    name: str = NAME

    def supports(self, q: str, category: str) -> bool:
        return category in _CODES

    async def fetch(self, client: httpx.AsyncClient, q: str, category: str, timeout_s: float) -> list[Result]:
        params = {"page": "rss", **({"q": q} if q else {}), "c": _CODES[category]}
        response = await get(client, f"{self.base.rstrip('/')}/?{urlencode(params)}", timeout_s, refused="refused the request")
        return parse(response.content, category)
