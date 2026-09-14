"""YouTube URL parsing. Pure — no network, no pytubefix."""

from __future__ import annotations

from urllib.parse import parse_qs, urlparse

_WATCH_HOSTS = frozenset({"youtube.com", "music.youtube.com"})
_PATH_PREFIXES = ("/embed/", "/shorts/")
_ALLOWED_SCHEMES = frozenset({"http", "https"})


def _first_segment(path: str) -> str:
    return path.lstrip("/").split("/", 1)[0]


def extract_video_id(url: str) -> str | None:
    """The video id in ``url``, or ``None`` if it is not a YouTube video link.

    Handles the five shapes the UI can produce: ``/watch?v=``, ``youtu.be/``,
    ``/embed/``, ``/shorts/``, and the ``music.`` host. ``www.`` and ``m.``
    prefixes are folded away first, so each form is written once.
    """
    parsed = urlparse((url or "").strip())
    if parsed.scheme not in _ALLOWED_SCHEMES:
        return None

    host = (parsed.hostname or "").removeprefix("www.").removeprefix("m.")

    if host == "youtu.be":
        return _first_segment(parsed.path) or None

    if host in _WATCH_HOSTS:
        if parsed.path == "/watch":
            values = parse_qs(parsed.query).get("v") or []
            return values[0] if values else None
        for prefix in _PATH_PREFIXES:
            if parsed.path.startswith(prefix):
                return _first_segment(parsed.path[len(prefix) :]) or None

    return None


def is_youtube_url(url: str) -> bool:
    return extract_video_id(url) is not None
