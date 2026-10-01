"""Torznab, the indexer API Prowlarr and Jackett speak: settings, requests and answers.

Nothing here touches the network, so every rule is tested with plain values.
"""

import re
from collections.abc import Sequence
from dataclasses import dataclass, replace
from datetime import datetime
from email.utils import parsedate_to_datetime
from typing import Literal
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from xml.etree import ElementTree

from src.lib.sources.source import Result, SourceError

_NAME = re.compile(r"^[a-z0-9-]+$")

#: The spec's categories and Torznab's top-level numbers; ``all`` sends none.
CATEGORIES: dict[str, str | None] = {
    "all": None,
    "movies": "2000",
    "tv": "5000",
    "music": "3000",
    "software": "4000",
    "books": "7000",
    "other": "8000",
}

#: Results asked of each indexer; the merged answer is capped by SEARCH_LIMIT.
PER_INDEXER = 100


@dataclass(frozen=True)
class Indexer:
    name: str
    url: str
    key: str | None = None

    @property
    def origin(self) -> tuple[str, str, int]:
        """Scheme, host and port: what a fetched link must share with this indexer."""
        parts = urlsplit(self.url)
        return parts.scheme, (parts.hostname or "").lower(), parts.port or (443 if parts.scheme == "https" else 80)


def parse_pairs(raw: str, setting: str) -> dict[str, str]:
    found: dict[str, str] = {}
    for part in (p.strip() for p in raw.split(",")):
        if not part:
            continue
        name, sep, value = part.partition("=")
        name, value = name.strip(), value.strip()
        if not sep or not value or not _NAME.match(name):
            raise ValueError(f"{setting}: '{part}' is not name=value, with a name of a-z, 0-9 and -")
        if name in found:
            raise ValueError(f"{setting}: {name} appears twice")
        found[name] = value
    return found


def parse_indexers(urls: str, keys: str) -> list[Indexer]:
    """``SEARCH_INDEXERS`` and ``SEARCH_INDEXER_KEYS``; a mistake raises, naming it."""
    by_name = parse_pairs(urls, "SEARCH_INDEXERS")
    key_by_name = parse_pairs(keys, "SEARCH_INDEXER_KEYS")
    unknown = sorted(set(key_by_name) - set(by_name))
    if unknown:
        raise ValueError(f"SEARCH_INDEXER_KEYS names {', '.join(unknown)}, which SEARCH_INDEXERS doesn't")
    for name, url in by_name.items():
        parts = urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise ValueError(f"SEARCH_INDEXERS: {name}'s URL must be http or https")
    return [Indexer(name, url, key_by_name.get(name)) for name, url in by_name.items()]


def build_url(indexer: Indexer, q: str, category: str) -> str:
    """The indexer's own URL with a Torznab search added to its query."""
    parts = urlsplit(indexer.url)
    query = parse_qsl(parts.query, keep_blank_values=True)
    query.append(("t", "search"))
    if q:
        query.append(("q", q))
    cat = CATEGORIES.get(category)
    if cat is not None:
        query.append(("cat", cat))
    query.append(("limit", str(PER_INDEXER)))
    if indexer.key:
        query.append(("apikey", indexer.key))
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), ""))


def redact(url: str) -> str:
    """A URL fit for a log: ``apikey``'s value becomes ``***``."""
    parts = urlsplit(url)
    if not parts.query:
        return url
    query = [(k, "***" if k == "apikey" else v) for k, v in parse_qsl(parts.query, keep_blank_values=True)]
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))


_ATTR = "{http://torznab.com/schemas/2015/feed}attr"
_BTIH = re.compile(r"xt=urn:btih:([A-Za-z0-9]+)")
#: Torznab's top-level category numbers by their first digit; anything else is "other".
_GROUPS = {"2": "movies", "5": "tv", "3": "music", "4": "software", "7": "books"}


class TorznabError(SourceError):
    """An indexer's answer that holds no results, in words fit to show."""


def _count(value: str | None) -> int | None:
    try:
        n = int(float(value)) if value not in (None, "") else None
    except ValueError:
        return None
    return n if n is not None and n >= 0 else None


def _date(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return parsedate_to_datetime(value)
    except (TypeError, ValueError):
        return None


def _category(value: str | None) -> str:
    return _GROUPS.get(value[0], "other") if value and value[0].isdigit() else "other"


def parse(body: bytes | str, indexer: str) -> list[Result]:
    """One indexer's Torznab answer; items with no title, or nothing to download, are dropped."""
    try:
        root = ElementTree.fromstring(body)
    except ElementTree.ParseError as error:
        raise TorznabError("unreadable answer") from error
    if root.tag == "error":
        raise TorznabError(root.get("description") or f"error {root.get('code', '')}".strip())
    results: list[Result] = []
    for item in root.iter("item"):
        title = (item.findtext("title") or "").strip()
        attrs = {a.get("name"): a.get("value") for a in item.findall(_ATTR)}
        enclosure = item.find("enclosure")
        enclosed = enclosure.get("url") if enclosure is not None else None
        linked = (item.findtext("link") or "").strip() or None
        candidates = [u for u in (enclosed, linked) if u]
        magnet = attrs.get("magneturl") or next((u for u in candidates if u.startswith("magnet:")), None)
        link = next((u for u in candidates if not u.startswith("magnet:")), None)
        if not title or not (magnet or link):
            continue
        size = _count(item.findtext("size")) or _count(attrs.get("size"))
        if size is None and enclosure is not None:
            size = _count(enclosure.get("length"))
        seeders = _count(attrs.get("seeders"))
        peers = _count(attrs.get("peers"))
        leechers = _count(attrs.get("leechers"))
        if leechers is None and peers is not None and seeders is not None:
            leechers = max(peers - seeders, 0)
        info_hash = attrs.get("infohash")
        if not info_hash and magnet:
            found = _BTIH.search(magnet)
            info_hash = found.group(1) if found else None
        results.append(
            Result(
                title=title,
                size=size,
                seeders=seeders,
                leechers=leechers,
                published=_date(item.findtext("pubDate")),
                category=_category(attrs.get("category") or item.findtext("category")),
                info_hash=info_hash.lower() if info_hash else None,
                magnet=magnet,
                link=link,
                indexers=(indexer,),
            )
        )
    return results


def _key(result: Result) -> tuple[str, ...]:
    return (result.info_hash,) if result.info_hash else (result.title.lower(), str(result.size))


def _most(a: int | None, b: int | None) -> int | None:
    return b if a is None else a if b is None else max(a, b)


def _copy_rank(result: Result) -> tuple[int, int, int]:
    """Which copy is better: a link beats a magnet, then more seeders, then known over unknown."""
    return (1 if result.link else 0, result.seeders or 0, 1 if result.seeders is not None else 0)


def _one_copy(result: Result) -> Result:
    """One copy only, named by its source: a link wins, and a result is its own best candidate."""
    return replace(
        result,
        magnet=None if result.link is not None else result.magnet,
        copy_from=result.copy_from or result.indexers[0],
    )


def _best_copy(seen: Result, candidate: Result) -> Result:
    """The one of two candidates' copies to hand over, named by the source it came from.

    The loser's field goes even when the winner carried both, so the result holds one copy.
    """
    winner = seen if _copy_rank(seen) >= _copy_rank(candidate) else candidate
    return _one_copy(winner)


def merge(per_indexer: list[list[Result]], limit: int, order: Literal["seeders", "newest"] = "seeders") -> list[Result]:
    """One result per torrent; most seeded first (``seeders``) or newest first (``newest``); at most ``limit``."""
    merged: dict[tuple[str, ...], Result] = {}
    for results in per_indexer:
        for result in results:
            key = _key(result)
            seen = merged.get(key)
            if seen is None:
                # Nothing merges into it, so the ranking never runs: it is its own
                # best candidate, and still has to end up with one named copy.
                merged[key] = _one_copy(result)
                continue
            best = _best_copy(seen, result)
            merged[key] = replace(
                seen,
                seeders=_most(seen.seeders, result.seeders),
                leechers=_most(seen.leechers, result.leechers),
                size=seen.size or result.size,
                published=seen.published or result.published,
                # The copy alone comes from the winner: `indexers` keeps arrival
                # order, which is not the copy's order.
                magnet=best.magnet,
                link=best.link,
                copy_from=best.copy_from,
                indexers=seen.indexers + tuple(n for n in result.indexers if n not in seen.indexers),
            )
    if order == "newest":
        ordered = sorted(
            merged.values(),
            key=lambda r: (r.published is None, -(r.published.timestamp() if r.published else 0), r.seeders is None, -(r.seeders or 0)),
        )
    else:
        ordered = sorted(merged.values(), key=lambda r: (r.seeders is None, -(r.seeders or 0), -(r.size or 0)))
    return ordered[:limit]


def link_allowed(link: str, indexers: Sequence[Indexer]) -> bool:
    """Whether ``link`` shares a configured indexer's scheme, host and port: the only links fetched."""
    parts = urlsplit(link)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
        return False
    try:
        port = parts.port or (443 if parts.scheme == "https" else 80)
    except ValueError:
        return False
    return any((parts.scheme, parts.hostname.lower(), port) == indexer.origin for indexer in indexers)
