"""apibay, The Pirate Bay's JSON API: search, and its latest list (spec: built-in sources)."""

import html
import json
import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from urllib.parse import urlencode

import httpx

from src.lib.sources.source import Result, SourceError, get, magnet_for, to_count

NAME = "apibay"
DEFAULT_URL = "https://apibay.org"

_HASH = re.compile(r"[0-9a-f]{40}")
_ADULT = range(500, 600)
#: The apibay codes behind each app category; "other" is what none of these keep.
_CODES: dict[str, Callable[[int], bool]] = {
    "movies": lambda code: code in (201, 202, 207, 209),
    "tv": lambda code: code in (205, 208),
    "music": lambda code: 100 <= code < 200,
    "software": lambda code: 300 <= code < 400,
    "books": lambda code: code == 601,
}
#: The group a search sends as ``cat=``; the results are then narrowed by ``_CODES``.
_GROUP = {"all": 0, "movies": 200, "tv": 200, "music": 100, "software": 300, "books": 600, "other": 0}


def _category(code: int) -> str:
    return next((name for name, has in _CODES.items() if has(code)), "other")


def _keeps(category: str, code: int) -> bool:
    if code in _ADULT:
        return False
    return category == "all" or _category(code) == category


def parse(body: bytes | str, category: str) -> list[Result]:
    """apibay's list (a search, or the latest) narrowed to ``category``; adult rows and the no-results row are dropped."""
    try:
        rows = json.loads(body)
    except ValueError as error:
        raise SourceError("unreadable answer") from error
    if not isinstance(rows, list):
        raise SourceError("unreadable answer")
    results: list[Result] = []
    for row in rows:
        if not isinstance(row, dict) or str(row.get("id")) == "0":
            continue
        name = html.unescape(str(row.get("name") or "")).strip()
        info_hash = str(row.get("info_hash") or "").lower()
        code = to_count(row.get("category"))
        if not name or not _HASH.fullmatch(info_hash) or set(info_hash) == {"0"} or code is None or not _keeps(category, code):
            continue
        added = to_count(row.get("added"))
        results.append(
            Result(
                title=name,
                size=to_count(row.get("size")),
                seeders=to_count(row.get("seeders")),
                leechers=to_count(row.get("leechers")),
                published=datetime.fromtimestamp(added, UTC) if added else None,
                category=_category(code),
                info_hash=info_hash,
                magnet=magnet_for(info_hash, name),
                link=None,
                indexers=(NAME,),
            )
        )
    return results


@dataclass(frozen=True)
class Apibay:
    base: str = DEFAULT_URL
    name: str = NAME

    def supports(self, q: str, category: str) -> bool:
        return True

    async def fetch(self, client: httpx.AsyncClient, q: str, category: str, timeout_s: float) -> list[Result]:
        base = self.base.rstrip("/")
        if q:
            url = f"{base}/q.php?{urlencode({'q': q, 'cat': _GROUP[category]})}"
        else:
            url = f"{base}/precompiled/data_top100_recent.json"
        response = await get(client, url, timeout_s, refused="refused the request")
        return parse(response.content, category)
