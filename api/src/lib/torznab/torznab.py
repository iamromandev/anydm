"""Torznab, the indexer API Prowlarr and Jackett speak: settings, requests and answers.

Nothing here touches the network, so every rule is tested with plain values.
"""

import re
from dataclasses import dataclass
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

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


def _pairs(raw: str, setting: str) -> dict[str, str]:
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
    by_name = _pairs(urls, "SEARCH_INDEXERS")
    key_by_name = _pairs(keys, "SEARCH_INDEXER_KEYS")
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
    query += [("t", "search"), ("q", q)]
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
