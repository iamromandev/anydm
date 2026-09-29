"""The built-in sources by name, and turning SEARCH_SOURCES and SEARCH_SOURCE_URLS into them."""

from collections.abc import Callable, Collection
from urllib.parse import urlsplit

from src.lib.sources.apibay import DEFAULT_URL as APIBAY_URL
from src.lib.sources.apibay import Apibay
from src.lib.sources.source import Source
from src.lib.torznab.torznab import parse_pairs

#: name -> (default base URL, a source for a base URL).
BUILTINS: dict[str, tuple[str, Callable[[str], Source]]] = {
    "apibay": (APIBAY_URL, lambda url: Apibay(base=url)),
}

DEFAULT_SOURCES = "apibay"


def parse_sources(names: str, urls: str, taken: Collection[str] = ()) -> list[Source]:
    """``SEARCH_SOURCES`` and ``SEARCH_SOURCE_URLS``; a mistake raises, naming it. ``taken`` holds the Torznab names."""
    wanted = [name.strip() for name in names.split(",") if name.strip()]
    seen: set[str] = set()
    for name in wanted:
        if name not in BUILTINS:
            raise ValueError(f"SEARCH_SOURCES: {name} isn't a built-in source (known: {', '.join(sorted(BUILTINS))})")
        if name in seen:
            raise ValueError(f"SEARCH_SOURCES: {name} appears twice")
        if name in taken:
            raise ValueError(f"SEARCH_SOURCES: {name} is also a SEARCH_INDEXERS name")
        seen.add(name)
    overrides = parse_pairs(urls, "SEARCH_SOURCE_URLS")
    stray = sorted(set(overrides) - seen)
    if stray:
        raise ValueError(f"SEARCH_SOURCE_URLS names {', '.join(stray)}, which SEARCH_SOURCES doesn't run")
    for name, url in overrides.items():
        parts = urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise ValueError(f"SEARCH_SOURCE_URLS: {name}'s URL must be http or https")
    return [BUILTINS[name][1](overrides.get(name, BUILTINS[name][0])) for name in wanted]
