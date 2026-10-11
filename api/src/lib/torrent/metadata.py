"""A ``.torrent``'s piece rows, read out of its bencoded info dict.

rqbit's control API gives a download's file list and its piece count, but not
the piece length or the per-piece SHA-1s — those live only in the torrent
file. This module is the reader: pure, so a piece row set can be built from
stored bytes with no engine and no network, which is exactly what ``enqueue``
does after the engine has answered.

Only BitTorrent v1 is read. A hybrid or v2 torrent still carries a v1 info
dict for compatibility, and that is the one whose pieces match the bitfield
``/haves`` reports.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from src.lib.torrent import error as torrent_error

#: A SHA-1, in bytes, as the ``pieces`` blob concatenates them.
_HASH_BYTES = 20


@dataclass(frozen=True, slots=True)
class PieceSpec:
    """One piece as it arrives from the torrent file."""

    index: int
    size: int
    hash: str


def piece_specs(torrent_bytes: bytes) -> list[PieceSpec]:
    """Every piece of the torrent ``torrent_bytes`` describes.

    Raises an ``Error`` for anything that is not a well-formed v1 ``.torrent``:
    the bytes come from a user's paste or an engine's session, and a half-read
    info dict would otherwise produce a piece table that silently disagrees
    with the engine's bitfield.
    """
    decoded = _decode(torrent_bytes)
    if not isinstance(decoded, Mapping):
        raise torrent_error.invalid_source("not a bencoded dictionary")
    info = decoded.get(b"info")
    if not isinstance(info, Mapping):
        raise torrent_error.invalid_source("no info dictionary")

    piece_length = info.get(b"piece length")
    hashes = info.get(b"pieces")
    if not isinstance(piece_length, int) or piece_length <= 0:
        raise torrent_error.invalid_source("no piece length")
    if not isinstance(hashes, bytes):
        raise torrent_error.invalid_source("no piece hashes")
    if len(hashes) % _HASH_BYTES:
        raise torrent_error.invalid_source("piece hashes are not whole 20-byte SHA-1s")

    total = _total_size(info)
    count = len(hashes) // _HASH_BYTES
    if total > piece_length * count or (count and total < piece_length * (count - 1)):
        raise torrent_error.invalid_source("piece count and total size disagree")
    if count == 0:
        return []

    specs = []
    for index in range(count):
        last = index == count - 1
        specs.append(
            PieceSpec(
                index=index,
                size=total - piece_length * (count - 1) if last else piece_length,
                hash=hashes[index * _HASH_BYTES : (index + 1) * _HASH_BYTES].hex(),
            )
        )
    return specs


def _total_size(info: Mapping[bytes, Any]) -> int:
    """The torrent's size: one ``length``, or the sum over ``files``."""
    if b"length" in info:
        length = info[b"length"]
        if isinstance(length, int):
            return length
        raise torrent_error.invalid_source("length is not an integer")
    files = info.get(b"files")
    if not isinstance(files, list) or not all(isinstance(f, Mapping) for f in files):
        raise torrent_error.invalid_source("no length and no file list")
    total = 0
    for entry in files:
        length = entry.get(b"length")
        if not isinstance(length, int):
            raise torrent_error.invalid_source("a file without a length")
        total += length
    return total


def _decode(data: bytes) -> Any:
    try:
        value, offset = _bencode(data, 0)
    except (ValueError, IndexError) as exc:
        # Truncated integers and lengths surface here, not in ``_bencode``'s
        # own checks: ``bytes.index`` and ``int`` raise before they run.
        raise torrent_error.invalid_source("not a bencoded torrent") from exc
    if offset != len(data):
        raise torrent_error.invalid_source("trailing bytes after the torrent")
    return value


def _bencode(data: bytes, offset: int) -> tuple[Any, int]:
    """One bencoded value and the offset just past it."""
    kind = data[offset : offset + 1]
    if kind == b"d":
        mapping: dict[bytes, Any] = {}
        offset += 1
        while data[offset : offset + 1] != b"e":
            key, offset = _bencode(data, offset)
            if not isinstance(key, bytes):
                raise torrent_error.invalid_source("a dictionary key that is not a byte string")
            mapping[key], offset = _bencode(data, offset)
        return mapping, offset + 1
    if kind == b"l":
        items = []
        offset += 1
        while data[offset : offset + 1] != b"e":
            item, offset = _bencode(data, offset)
            items.append(item)
        return items, offset + 1
    if kind == b"i":
        end = data.index(b"e", offset)
        try:
            return int(data[offset + 1 : end]), end + 1
        except ValueError as exc:
            raise torrent_error.invalid_source("an integer that is not one") from exc
    if kind.isdigit():
        colon = data.index(b":", offset)
        length = int(data[offset:colon])
        start = colon + 1
        return data[start : start + length], start + length
    raise torrent_error.invalid_source("a value that is not bencoded")
