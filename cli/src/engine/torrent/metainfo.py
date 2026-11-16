"""Parse a v1 .torrent: infohash, piece hashes, and file paths that are safe to write (NFR3)."""

import hashlib
import unicodedata
from dataclasses import dataclass, field, replace
from pathlib import Path

from src.core.names import clean_name
from src.engine.torrent.bencode import BencodeError, Value, decode_top

MAX_PIECES = 1 << 21
MAX_PIECE_LENGTH = 32 << 20
MAX_TIERS = 16
MAX_TRACKERS = 64


class MetainfoError(ValueError):
    pass


@dataclass(frozen=True)
class FileEntry:
    path: tuple[str, ...]  # already cleaned; relative to the destination folder
    length: int
    offset: int  # of the file's first byte in the torrent's concatenated payload
    pad: bool = False  # BEP 47 padding: never written to disk, always zeros in the payload


@dataclass(frozen=True)
class Torrent:
    infohash: bytes
    name: str
    piece_length: int
    pieces: tuple[bytes, ...]
    files: tuple[FileEntry, ...]
    total: int
    tiers: list[list[str]] = field(default_factory=list)
    private: bool = False  # BEP 27: only the trackers may be used to find peers
    info: bytes = field(default=b"", repr=False, compare=False)  # the bencoded info dictionary as hashed, to serve (BEP 9)

    @property
    def piece_count(self) -> int:
        return len(self.pieces)

    def piece_size(self, index: int) -> int:
        return min(self.piece_length, self.total - index * self.piece_length)

    def with_root(self, root: str) -> Torrent:
        """The same torrent with its top-level name replaced (used to avoid overwriting something else)."""
        files = tuple(f if f.pad else replace(f, path=(root, *f.path[1:])) for f in self.files)
        return replace(self, files=files)

    def file_paths(self, dest: Path) -> list[Path]:
        """Absolute path of every real file under `dest`; raises when one would resolve outside it."""
        root = dest.resolve()
        out = []
        for f in self.files:
            if f.pad:
                continue
            p = root.joinpath(*f.path)
            if not p.resolve().is_relative_to(root) or p == root:
                raise MetainfoError(f"unsafe path in torrent: {'/'.join(f.path)}")
            out.append(p)
        return out


def _text(v: Value | None) -> str:
    return v.decode("utf-8", errors="replace") if isinstance(v, bytes) else ""


def _clean_parts(parts: list[str]) -> tuple[str, ...]:
    return tuple(clean_name(p, "_") for p in parts)


def _key(parts: list[str]) -> tuple[str, ...]:
    return tuple(unicodedata.normalize("NFC", c).casefold() for c in parts)


def _unique(paths: list[tuple[str, ...]]) -> list[tuple[str, ...]]:
    """Make the paths safe to create side by side, also on a case-insensitive or normalizing file system:
    no two names equal after NFC and casefold, and nothing is both a file and a directory. The later
    of two clashing paths gets ` (n)` appended to the clashing component."""
    files: set[tuple[str, ...]] = set()
    dirs: set[tuple[str, ...]] = set()
    counters: dict[tuple[tuple[str, ...], str], int] = {}
    dir_names: dict[tuple[tuple[str, ...], str], str] = {}  # keeps a renamed directory's files together
    out = []
    for p in paths:
        parts = list(p)
        originals: dict[int, str] = {}
        while True:
            k = _key(parts)
            last = len(parts) - 1
            clash_below = next((j - 1 for j in range(1, len(parts)) if k[:j] in files), -1)  # a parent that is a file
            idx = last if k in files or k in dirs else clash_below
            if idx < 0:
                break
            base = originals.setdefault(idx, parts[idx])
            tag = (k[:idx], _key([base])[0])
            known = dir_names.get(tag)
            if idx < last and known is not None and parts[idx] != known:
                parts[idx] = known
                continue
            n = counters.get(tag, 0) + 1
            counters[tag] = n
            parts[idx] = f"{base} ({n})"
            if idx < last:
                dir_names[tag] = parts[idx]
        files.add(k)
        dirs.update(k[:j] for j in range(1, len(k)))
        out.append(tuple(parts))
    return out


def _tiers(meta: dict[bytes, Value]) -> list[list[str]]:
    """The announce tiers, at most MAX_TIERS of them and MAX_TRACKERS trackers in all."""
    tiers: list[list[str]] = []
    room = MAX_TRACKERS
    listed = meta.get(b"announce-list")
    if isinstance(listed, list):
        for tier in listed:
            if isinstance(tier, list) and len(tiers) < MAX_TIERS and room > 0:
                urls = [_text(u) for u in tier if isinstance(u, bytes)][:room]
                if urls:
                    tiers.append(urls)
                    room -= len(urls)
    if not tiers and isinstance(meta.get(b"announce"), bytes):
        tiers.append([_text(meta[b"announce"])])
    return tiers


def parse(raw: bytes) -> Torrent:
    try:
        meta, spans = decode_top(raw)
    except BencodeError as e:
        raise MetainfoError(f"not a valid .torrent file: {e}") from e
    info = meta.get(b"info")
    if not isinstance(info, dict):
        raise MetainfoError("missing info dictionary")
    if b"meta version" in info or b"file tree" in info:
        raise MetainfoError("BitTorrent v2 and hybrid torrents are not supported yet")
    plen, pieces = info.get(b"piece length"), info.get(b"pieces")
    if not isinstance(plen, int) or isinstance(plen, bool) or plen <= 0:
        raise MetainfoError("bad piece length")
    if plen > MAX_PIECE_LENGTH:
        raise MetainfoError("piece length is larger than 32 MiB")
    if not isinstance(pieces, bytes) or not pieces or len(pieces) % 20 or len(pieces) // 20 > MAX_PIECES:
        raise MetainfoError("bad pieces")
    name = clean_name(_text(info.get(b"name")), "download")

    sizes: list[int] = []
    pads: list[bool] = []
    raw_paths: list[list[str]] = []
    listing = info.get(b"files")
    if listing is None:
        length = info.get(b"length")
        if not isinstance(length, int) or isinstance(length, bool) or length < 0:
            raise MetainfoError("bad file length")
        sizes, pads, raw_paths = [length], [False], []
        real = [(name,)]
    else:
        if not isinstance(listing, list) or not listing:
            raise MetainfoError("bad file list")
        for entry in listing:
            if not isinstance(entry, dict):
                raise MetainfoError("bad file entry")
            length, parts = entry.get(b"length"), entry.get(b"path")
            if not isinstance(length, int) or isinstance(length, bool) or length < 0:
                raise MetainfoError("bad file length")
            if not isinstance(parts, list) or not parts or not all(isinstance(p, bytes) for p in parts):
                raise MetainfoError("bad file path")
            attr = entry.get(b"attr")
            sizes.append(length)
            pads.append(isinstance(attr, bytes) and b"p" in attr)
            raw_paths.append([_text(p) for p in parts])
        real = _unique([(name, *_clean_parts(p)) for p, pad in zip(raw_paths, pads, strict=True) if not pad])

    total = sum(sizes)
    if total <= 0 or -(-total // plen) != len(pieces) // 20:
        raise MetainfoError("piece count does not match the total size")
    files, offset = [], 0
    names = iter(real)
    for n, pad in zip(sizes, pads, strict=True):
        files.append(FileEntry((), n, offset, True) if pad else FileEntry(next(names), n, offset))
        offset += n
    s, e = spans[b"info"]
    return Torrent(
        infohash=hashlib.sha1(raw[s:e]).digest(),
        info=raw[s:e],
        name=name,
        piece_length=plen,
        pieces=tuple(pieces[i : i + 20] for i in range(0, len(pieces), 20)),
        files=tuple(files),
        total=total,
        tiers=_tiers(meta),
        private=info.get(b"private") == 1,
    )
