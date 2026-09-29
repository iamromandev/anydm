"""EZTV's JSON API: the latest TV releases. It has no text search, so this source only browses."""

import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime

import httpx

from src.lib.sources.source import Result, SourceError, get, magnet_for, to_count

NAME = "eztv"
DEFAULT_URL = "https://eztvx.to"

_HASH = re.compile(r"[0-9a-f]{40}")


def parse(body: bytes | str) -> list[Result]:
    """EZTV's ``torrents`` list; entries without a valid hash are dropped."""
    try:
        payload = json.loads(body)
    except ValueError as error:
        raise SourceError("unreadable answer") from error
    rows = payload.get("torrents") if isinstance(payload, dict) else None
    if not isinstance(rows, list):
        raise SourceError("unreadable answer")
    results: list[Result] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        title = str(row.get("title") or row.get("filename") or "").strip()
        info_hash = str(row.get("hash") or "").strip().lower()
        if not title or not _HASH.fullmatch(info_hash):
            continue
        released = to_count(row.get("date_released_unix"))
        results.append(
            Result(
                title=title,
                size=to_count(row.get("size_bytes")),
                seeders=to_count(row.get("seeds")),
                leechers=to_count(row.get("peers")),
                published=datetime.fromtimestamp(released, UTC) if released else None,
                category="tv",
                info_hash=info_hash,
                magnet=str(row.get("magnet_url") or "") or magnet_for(info_hash, title),
                link=None,
                indexers=(NAME,),
            )
        )
    return results


@dataclass(frozen=True)
class Eztv:
    base: str = DEFAULT_URL
    name: str = NAME

    def supports(self, q: str, category: str) -> bool:
        return q == "" and category in ("all", "tv")

    async def fetch(self, client: httpx.AsyncClient, q: str, category: str, timeout_s: float) -> list[Result]:
        url = f"{self.base.rstrip('/')}/api/get-torrents?limit=100&page=1"
        response = await get(client, url, timeout_s, refused="refused the request")
        return parse(response.content)
