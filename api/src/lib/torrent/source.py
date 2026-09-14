"""User input as something the engine can take.

rqbit's add endpoint accepts three body shapes: a magnet string, an http link
to a ``.torrent``, or the file's raw bytes. The UI offers one field, exactly as
the old Bun API did, so the discrimination happens here — pure, and tested
without a network.
"""

from __future__ import annotations

import base64
import binascii
from dataclasses import dataclass

from src.lib.torrent import error as torrent_error

_MAGNET_PREFIX = "magnet:?"
_URL_PREFIXES = ("http://", "https://")
_DATA_URL_MARKER = ";base64,"
#: Every bencoded ``.torrent`` is a dictionary, and bencode spells that "d".
_BENCODE_PREFIX = b"d"


@dataclass(frozen=True, slots=True)
class TorrentSource:
    """Exactly one of ``text`` or ``blob`` is set."""

    text: str | None = None
    blob: bytes | None = None

    @property
    def is_blob(self) -> bool:
        return self.blob is not None


def parse_source(raw: str) -> TorrentSource:
    """A magnet, an http link, or a base64 ``.torrent`` — whichever this is."""
    value = raw.strip()
    if not value:
        raise torrent_error.invalid_source("Empty torrent input")

    lowered = value.lower()
    if lowered.startswith(_MAGNET_PREFIX) or lowered.startswith(_URL_PREFIXES):
        return TorrentSource(text=value)

    # A browser's FileReader hands back a data URL, not bare base64.
    if _DATA_URL_MARKER in value:
        value = value.split(_DATA_URL_MARKER, 1)[1]

    try:
        blob = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise torrent_error.invalid_source(
            "Not a magnet link, an http URL, or a base64 .torrent file"
        ) from exc

    if not blob.startswith(_BENCODE_PREFIX):
        raise torrent_error.invalid_source("Decoded data is not a .torrent file")
    return TorrentSource(blob=blob)
