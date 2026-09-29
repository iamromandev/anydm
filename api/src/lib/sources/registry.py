"""The built-in sources: what code must know about each, declared once as constants."""

from collections.abc import Callable, Collection
from dataclasses import dataclass
from urllib.parse import urlsplit

from src.lib.sources.apibay import DEFAULT_URL as APIBAY_URL
from src.lib.sources.apibay import Apibay
from src.lib.sources.eztv import DEFAULT_URL as EZTV_URL
from src.lib.sources.eztv import Eztv
from src.lib.sources.nyaa import DEFAULT_URL as NYAA_URL
from src.lib.sources.nyaa import Nyaa
from src.lib.sources.source import Source
from src.lib.torznab.torznab import parse_pairs


@dataclass(frozen=True)
class Builtin:
    """What code must know about one built-in source, declared once (spec: source management)."""

    name: str
    label: str
    default_url: str
    make: Callable[[str], Source]
    #: What Test asks for; "" browses the latest.
    test_query: str = ""
    default_enabled: bool = True


#: In the order clients list them.
BUILTINS: dict[str, Builtin] = {
    builtin.name: builtin
    for builtin in (
        Builtin("apibay", "apibay", APIBAY_URL, lambda url: Apibay(base=url), test_query="ubuntu"),
        Builtin("nyaa", "Nyaa", NYAA_URL, lambda url: Nyaa(base=url)),
        Builtin("eztv", "EZTV", EZTV_URL, lambda url: Eztv(base=url)),
    )
}

DEFAULT_SOURCES = "apibay,nyaa,eztv"


def validate_base_url(text: str) -> str:
    """The address trimmed and without trailing slashes, or a ValueError saying what is wrong."""
    url = text.strip().rstrip("/")
    if not url or any(character.isspace() for character in url):
        raise ValueError("The address can't be empty or contain spaces")
    parts = urlsplit(url)
    try:
        parts.port  # noqa: B018  (reading it is what validates the port)
    except ValueError as error:
        raise ValueError("The address has an invalid port") from error
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise ValueError("The address must be http or https, with a host")
    return url


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
    return [BUILTINS[name].make(overrides.get(name, BUILTINS[name].default_url)) for name in wanted]
