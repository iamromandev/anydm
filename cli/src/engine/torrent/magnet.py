"""Magnet links (BEP 9): the infohash, a display name and tracker URLs. The link is user input but may well be
pasted from anywhere, so it is capped, and error messages never repeat it (a tracker URL can carry a passkey)."""

import base64
import binascii
import re
from dataclasses import dataclass, field
from urllib.parse import unquote, unquote_plus

from src.core.errors import UsageError
from src.core.names import clean_name
from src.engine.torrent.metainfo import MAX_TRACKERS

MAX_FIELDS = 2000
MAX_TRACKER_URL = 2048
_HEX = re.compile(r"[0-9a-fA-F]{40}")
_B32 = re.compile(r"[A-Za-z2-7]{32}")


@dataclass(frozen=True)
class Magnet:
    infohash: bytes
    name: str | None = None
    trackers: list[str] = field(default_factory=list)


def _infohash(xts: list[str]) -> bytes:
    found_v2 = False
    for xt in xts:
        low = xt.lower()
        if low.startswith("urn:btmh:"):
            found_v2 = True
        elif low.startswith("urn:btih:"):
            h = xt[len("urn:btih:") :]
            if _HEX.fullmatch(h):
                return bytes.fromhex(h)
            if _B32.fullmatch(h):
                try:
                    return base64.b32decode(h.upper())
                except binascii.Error:  # pragma: no cover - the pattern already admits only valid digits
                    pass
            raise UsageError("the magnet link has an invalid infohash (expected 40 hex or 32 base32 characters)")
    if found_v2:
        raise UsageError("this magnet link is for a BitTorrent v2 torrent only (urn:btmh), which is not supported yet")
    raise UsageError("the magnet link has no xt=urn:btih: infohash")


def parse_magnet(uri: str) -> Magnet:
    if not uri.lower().startswith("magnet:"):
        raise UsageError("not a magnet link")
    query = uri.partition("?")[2]
    xts: list[str] = []
    trackers: list[str] = []
    name: str | None = None
    for part in query.split("&", MAX_FIELDS)[:MAX_FIELDS]:
        key, _, value = part.partition("=")
        # a `+` in a tracker URL is a plus; only the form-encoded fields (dn) use it for a space
        value = unquote(value) if key == "tr" else unquote_plus(value)
        if key == "xt":
            xts.append(value)
        elif key == "dn" and name is None:
            name = clean_name(value, "download")
        elif key == "tr":
            low = value.lower()
            if (
                len(value) <= MAX_TRACKER_URL
                and low.startswith(("http://", "https://", "udp://"))
                and len(trackers) < MAX_TRACKERS
                and value not in trackers
            ):
                trackers.append(value)
    return Magnet(_infohash(xts), name, trackers)
