"""What a download is, as the pair ``(provider, ref_id)``.

Every download carries one. The provider says whose id it is, so two systems
that hand out the same id never meet; the ref is that system's own id for the
thing. A site's provider is the name yt-dlp gives its extractor (``Youtube``,
``Vimeo``), spelled exactly as it gives it, because that is what a picker and a
playlist already pass. The two built-in providers are lowercase constants.
"""

from __future__ import annotations

from src.core.url import normalize_url, url_hash

#: A direct link: its ref is the hash of its normalized address.
HTTP_PROVIDER = "http"
#: A torrent: its ref is its info hash.
TORRENT_PROVIDER = "torrent"


def url_ref(url: str) -> str:
    """The ref of a direct link: a fixed 64-character hash, which a long address cannot outgrow in an index."""
    return url_hash(normalize_url(url))
