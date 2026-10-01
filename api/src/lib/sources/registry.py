"""The source kinds: what code must know about each, declared once as constants."""

import re
from collections.abc import Callable
from dataclasses import dataclass
from urllib.parse import urlsplit

from src.lib.sources.apibay import DEFAULT_URL as APIBAY_URL
from src.lib.sources.apibay import Apibay
from src.lib.sources.eztv import DEFAULT_URL as EZTV_URL
from src.lib.sources.eztv import Eztv
from src.lib.sources.nyaa import DEFAULT_URL as NYAA_URL
from src.lib.sources.nyaa import Nyaa
from src.lib.sources.source import Source
from src.lib.sources.torznab_source import TorznabSource
from src.lib.torznab.torznab import Indexer

#: Every kind a source can be: the three built-in parsers and Torznab.
KINDS: tuple[str, ...] = ("torznab", "apibay", "nyaa", "eztv")

_NAME = re.compile(r"^[a-z0-9-]+$")


def valid_name(name: str) -> bool:
    """A source name: lowercase letters, digits and dashes, fitting the column."""
    return bool(name) and len(name) <= 64 and _NAME.match(name) is not None


@dataclass(frozen=True)
class Builtin:
    """What code must know about one built-in source, declared once (spec: source management)."""

    name: str
    default_url: str
    make: Callable[[str], Source]
    #: What Test asks for; "" browses the latest.
    test_query: str = ""
    default_enabled: bool = True


#: In the order clients list them.
BUILTINS: dict[str, Builtin] = {
    builtin.name: builtin
    for builtin in (
        Builtin("apibay", APIBAY_URL, lambda url: Apibay(base=url), test_query="ubuntu"),
        Builtin("nyaa", NYAA_URL, lambda url: Nyaa(base=url)),
        Builtin("eztv", EZTV_URL, lambda url: Eztv(base=url)),
    )
}


def default_url(kind: str) -> str | None:
    """The registry's address for a built-in kind; Torznab indexers have no default to go back to."""
    if kind == "torznab":
        return None
    return BUILTINS[kind].default_url


def make_source(kind: str, name: str, base_url: str, api_key: str | None) -> Source:
    """The parser for a stored row; an unknown kind is a ValueError, never a silent anything."""
    if kind == "torznab":
        return TorznabSource(Indexer(name, base_url, api_key))
    try:
        return BUILTINS[kind].make(base_url)
    except KeyError as error:
        raise ValueError(f"unknown source kind: {kind}") from error


def test_query_for(kind: str) -> str:
    """What Test asks for: a real query where a browse is likelier to fail, else a browse of the latest."""
    if kind in ("apibay", "torznab"):
        return "ubuntu"
    return ""

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
