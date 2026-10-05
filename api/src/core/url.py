"""One address, one spelling: the normal form a URL is stored and compared by.

Both the data layer (a ``Url`` row is unique by ``url_hash``) and
``src.lib.identity`` (a direct link's ref) hash the same normal form, so they
live here, below both.
"""

from __future__ import annotations

import hashlib
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

#: Parameters that say where a visitor came from, not what the address serves.
_TRACKING_PREFIXES = ("utm_",)
_TRACKING_NAMES = frozenset({"fbclid", "gclid", "mc_cid", "mc_eid"})


def normalize_url(url: str) -> str:
    """The address with case folded where it does not matter and the noise taken out.

    Scheme and host ignore case; the path does not. The fragment and tracking
    parameters are dropped, and the rest of the query is sorted, so one file
    reached by two spellings of its address has one identity.
    """
    parts = urlsplit(url.strip())
    query = sorted(
        (name, value)
        for name, value in parse_qsl(parts.query, keep_blank_values=True)
        if name not in _TRACKING_NAMES and not name.startswith(_TRACKING_PREFIXES)
    )
    return urlunsplit((parts.scheme.lower(), parts.netloc.lower(), parts.path, urlencode(query), ""))


def url_hash(normalized: str) -> str:
    """SHA-256 of an already normalized address: 64 characters, which no address can outgrow in an index."""
    return hashlib.sha256(normalized.encode()).hexdigest()
