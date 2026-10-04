"""What a download is, as the pair ``(provider, ref_id)``.

Every download carries one. The provider says whose id it is, so two systems
that hand out the same id never meet; the ref is that system's own id for the
thing. A site's provider is the name yt-dlp gives its extractor (``Youtube``,
``Vimeo``), spelled exactly as it gives it, because that is what a picker and a
playlist already pass. The two built-in providers are lowercase constants.
"""

from __future__ import annotations

import hashlib
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

#: A direct link: its ref is the hash of its normalized address.
HTTP_PROVIDER = "http"
#: A torrent: its ref is its info hash.
TORRENT_PROVIDER = "torrent"

#: Parameters that say where a visitor came from, not what the address serves.
_TRACKING_PREFIXES = ("utm_",)
_TRACKING_NAMES = frozenset({"fbclid", "gclid", "mc_cid", "mc_eid"})


def _normalized(url: str) -> str:
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


def url_ref(url: str) -> str:
    """The ref of a direct link: a fixed 64-character hash, which a long address cannot outgrow in an index."""
    return hashlib.sha256(_normalized(url).encode()).hexdigest()
