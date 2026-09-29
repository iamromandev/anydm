"""The built-in sources: what code must know about each, declared once as constants."""

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
