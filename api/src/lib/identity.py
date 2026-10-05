"""What a download is, as the pair ``(provider, ref_id)``.

Every download carries one. The provider says whose id it is, so two systems
that hand out the same id never meet; the ref is that system's own id for the
thing. A site's provider is the name yt-dlp gives its extractor (``Youtube``,
``Vimeo``), spelled exactly as it gives it, because that is what a picker and a
playlist already pass. The two built-in providers are lowercase constants.
"""

from __future__ import annotations

from functools import cache

from yt_dlp.extractor import gen_extractor_classes, get_info_extractor
from yt_dlp.extractor.common import InfoExtractor

from src.core.url import normalize_url, url_hash

#: A direct link: its ref is the hash of its normalized address.
HTTP_PROVIDER = "http"
#: A torrent: its ref is its info hash.
TORRENT_PROVIDER = "torrent"


def url_ref(url: str) -> str:
    """The ref of a direct link: a fixed 64-character hash, which a long address cannot outgrow in an index."""
    return url_hash(normalize_url(url))


@cache
def _site_extractors() -> tuple[type[InfoExtractor], ...]:
    """yt-dlp's extractors, but not the generic one, which suits any address and knows no id."""
    return tuple(ie for ie in gen_extractor_classes() if ie.ie_key() != "Generic")


def _temp_id(extractor: str, url: str) -> str | None:
    try:
        ie = get_info_extractor(extractor)
    except KeyError:
        return None
    return ie.get_temp_id(url)


@cache
def site_ref(extractor: str, url: str) -> str:
    """A site's own id for the page at ``url``: a video's, a playlist's or a channel tab's.

    Not stored: read off the address the way yt-dlp matches it, with no request.
    The named extractor first; a collection is stored under its site's name
    (``Youtube``) while its address is its tab extractor's (``YoutubeTab``), so
    then whichever extractor suits the address. A page only the generic
    extractor takes has no id of its own: its address hash stands in, as for a
    direct link.
    """
    found = _temp_id(extractor, url)
    if found is None:
        found = next((ie.get_temp_id(url) for ie in _site_extractors() if ie.suitable(url)), None)
    return found or url_ref(url)
