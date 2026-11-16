import hashlib
from pathlib import Path

from src.core.progress import NullReporter
from src.engine.torrent.bencode import decode_top, encode


def make_torrent(
    files: dict[str, bytes],
    piece_len: int = 16384,
    name: str = "pack",
    announce: str | None = "http://127.0.0.1:1/announce",
    tiers: list[list[str]] | None = None,
    single: bool = False,
    raw_paths: dict[str, list[bytes]] | None = None,
    attrs: dict[str, bytes] | None = None,
    private: bool = False,
) -> tuple[bytes, bytes]:
    """A .torrent for `files` (relative path -> content), and the payload they concatenate to."""
    payload = b"".join(files.values())
    pieces = b"".join(hashlib.sha1(payload[i : i + piece_len]).digest() for i in range(0, len(payload), piece_len))
    info: dict[bytes, object] = {b"name": name.encode(), b"piece length": piece_len, b"pieces": pieces}
    if private:
        info[b"private"] = 1
    if single:
        info[b"length"] = len(payload)
    else:
        listing = []
        for rel, data in files.items():
            parts = raw_paths[rel] if raw_paths and rel in raw_paths else [p.encode() for p in rel.split("/")]
            entry: dict[bytes, object] = {b"length": len(data), b"path": parts}
            if attrs and rel in attrs:
                entry[b"attr"] = attrs[rel]
            listing.append(entry)
        info[b"files"] = listing
    meta: dict[bytes, object] = {b"info": info}
    if announce:
        meta[b"announce"] = announce.encode()
    if tiers:
        meta[b"announce-list"] = [[u.encode() for u in t] for t in tiers]
    return encode(meta), payload


def write_torrent(path: Path, files: dict[str, bytes], **kw) -> bytes:
    raw, payload = make_torrent(files, **kw)
    path.write_bytes(raw)
    return payload


def info_bytes(raw: bytes) -> bytes:
    """The bencoded info dictionary of a .torrent, exactly as it is in the file."""
    _, spans = decode_top(raw)
    s, e = spans[b"info"]
    return raw[s:e]


class Recorder(NullReporter):
    """A reporter that keeps everything it is told."""

    def __init__(self) -> None:
        self.updates: list = []
        self.notices: list[str] = []

    def update(self, p) -> None:
        self.updates.append(p)

    def notice(self, msg: str) -> None:
        self.notices.append(msg)
