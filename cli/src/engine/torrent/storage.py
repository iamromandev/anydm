"""Piece to file mapping with positional IO, preallocation, recheck and the resume record.

Files are written in place. Descriptors are opened lazily through a small LRU, so a torrent with thousands of
files never runs out of them. The record `<infohash>.anydm.json` keeps the bitfield of verified pieces, the
top-level name the torrent was saved under, and each file's size and mtime when it was saved; if any file
differs the bitfield is stale and the caller rechecks."""

import bisect
import contextlib
import errno
import hashlib
import json
import os
import secrets
import shutil
import threading
from collections import OrderedDict
from collections.abc import Callable, Iterator
from pathlib import Path

from src.core.errors import DownloadError
from src.core.names import clean_name
from src.engine.torrent.metainfo import Torrent

VERSION = 1
MAX_OPEN = 64  # descriptors kept open at once
HASH_CHUNK = 1 << 20
MAX_ROOT_TRIES = 1000


class RecheckCancelled(Exception):
    pass


def _pread_all(fd: int, length: int, offset: int) -> bytes:
    out = b""
    while len(out) < length:
        chunk = os.pread(fd, length - len(out), offset + len(out))
        if not chunk:
            break
        out += chunk
    return out


def _free_space(path: Path) -> int:
    while not path.exists() and path != path.parent:
        path = path.parent
    return shutil.disk_usage(path).free


class Storage:
    """`create=False` opens nothing for writing and creates nothing: files that are missing read as empty."""

    def __init__(self, torrent: Torrent, dest: Path, create: bool = True) -> None:
        self.torrent = torrent
        self._dest = dest.resolve()
        self.record_path = self._dest / f"{torrent.infohash.hex()}.anydm.json"
        real = iter(torrent.file_paths(dest))  # raises when one would leave dest
        self._paths: list[Path | None] = [None if f.pad else next(real) for f in torrent.files]
        self._starts = [f.offset for f in torrent.files]
        self._create = create
        self._lock = threading.RLock()  # guards the descriptor cache and the dirty set
        self._save_lock = threading.Lock()  # one record write at a time
        self._fds: OrderedDict[int, int] = OrderedDict()
        self._dirty: set[int] = set()
        self._closed = False
        if create:
            try:
                self._prepare()
            except BaseException:
                self.close()
                raise

    # --- descriptors ---

    def _prepare(self) -> None:
        """Check the free space, create the folders and files, and give every file its full length."""
        need = 0
        for path, f in zip(self._paths, self.torrent.files, strict=True):
            if path is not None:
                try:
                    have = os.lstat(path).st_size
                except OSError:
                    have = 0
                need += max(0, f.length - have)
        free = _free_space(self._dest)
        if need > free:
            raise DownloadError(
                f"not enough free disk space in {self._dest}: {need} bytes needed, {free} available"
            )
        for i, (path, f) in enumerate(zip(self._paths, self.torrent.files, strict=True)):
            if path is None:
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            with self._lock:
                fd = self._fd(i)
                if os.fstat(fd).st_size != f.length:
                    os.ftruncate(fd, f.length)

    def _fd(self, i: int) -> int:
        """The descriptor of file `i`, opened now if it is not cached. Caller holds the lock."""
        if self._closed:
            raise OSError(errno.EBADF, "the storage is closed")
        fd = self._fds.get(i)
        if fd is not None:
            self._fds.move_to_end(i)
            return fd
        path = self._paths[i]
        assert path is not None
        if not path.parent.resolve().is_relative_to(self._dest):  # checked at every open, not just the first
            raise OSError(f"{path.parent} leaves the output folder")
        flags = (os.O_RDWR | os.O_CREAT) if self._create else os.O_RDONLY
        fd = os.open(path, flags | os.O_NOFOLLOW, 0o644)
        while len(self._fds) >= MAX_OPEN:
            os.close(self._fds.popitem(last=False)[1])
        self._fds[i] = fd
        return fd

    def close(self) -> None:
        with self._lock:
            self._closed = True
            for fd in self._fds.values():
                os.close(fd)
            self._fds.clear()

    # --- positional IO ---

    def _spans(self, offset: int, length: int) -> Iterator[tuple[int, int, int]]:
        """(file index, offset in file, byte count) for the payload range."""
        end = offset + length
        i = bisect.bisect_right(self._starts, offset) - 1
        while offset < end and i < len(self.torrent.files):
            f = self.torrent.files[i]
            n = min(end, f.offset + f.length) - offset
            if n > 0:
                yield i, offset - f.offset, n
                offset += n
            i += 1

    def _pread(self, i: int, offset: int, n: int) -> bytes:
        if self._paths[i] is None:
            return bytes(n)  # a pad file is zeros
        with self._lock:
            try:
                return _pread_all(self._fd(i), n, offset)
            except FileNotFoundError:
                if self._create:
                    raise
                return b""

    def _write(self, offset: int, data: bytes) -> None:
        pos = 0
        for i, off, n in self._spans(offset, len(data)):
            if self._paths[i] is not None:
                view = memoryview(data)[pos : pos + n]
                with self._lock:
                    fd = self._fd(i)
                    done = 0
                    while done < n:
                        done += os.pwrite(fd, view[done:], off + done)
                    self._dirty.add(i)
            pos += n

    def write_piece(self, index: int, data: bytes) -> None:
        self._write(index * self.torrent.piece_length, data)

    def read(self, index: int, begin: int, length: int) -> bytes:
        """Up to `length` bytes of a piece; shorter if the data is missing or the range runs off the torrent."""
        offset = index * self.torrent.piece_length + begin
        out = b""
        for i, off, n in self._spans(offset, length):
            chunk = self._pread(i, off, n)
            out += chunk
            if len(chunk) < n:
                break
        return out

    def piece_ok(self, index: int) -> bool:
        """Hash the piece from disk in chunks, so a big piece never sits in memory whole."""
        h = hashlib.sha1()
        for i, off, n in self._spans(index * self.torrent.piece_length, self.torrent.piece_size(index)):
            for pos in range(0, n, HASH_CHUNK):
                want = min(HASH_CHUNK, n - pos)
                chunk = self._pread(i, off + pos, want)
                if len(chunk) < want:
                    return False
                h.update(chunk)
        return h.digest() == self.torrent.pieces[index]

    def recheck(
        self, on_progress: Callable[[int], None] | None = None, cancel: threading.Event | None = None
    ) -> set[int]:
        """The verified pieces. Checks `cancel` before every piece and raises RecheckCancelled when it is set."""
        have = set()
        for i in range(self.torrent.piece_count):
            if cancel is not None and cancel.is_set():
                raise RecheckCancelled
            if self.piece_ok(i):
                have.add(i)
            if on_progress:
                on_progress(i + 1)
        return have

    # --- resume record ---

    def _root(self) -> str:
        return next((f.path[0] for f in self.torrent.files if not f.pad), "")

    def _stamp(self) -> list[list[int]]:
        out = []
        for path in self._paths:
            if path is not None:
                try:
                    st = os.stat(path, follow_symlinks=False)
                    out.append([st.st_size, st.st_mtime_ns])
                except OSError:
                    out.append([-1, -1])
        return out

    def _sync_dirty(self) -> None:
        with self._lock:
            dirty = sorted(self._dirty)
            self._dirty.clear()
        for n, i in enumerate(dirty):
            path = self._paths[i]
            assert path is not None
            try:
                fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            except OSError:
                continue  # gone: the stamp will not match, and the next run rechecks
            try:
                os.fsync(fd)
            except OSError:
                with self._lock:
                    self._dirty.update(dirty[n:])
                raise
            finally:
                os.close(fd)

    def save(self, have: set[int], checked: bool = True) -> None:
        """Write the record. `checked=False` marks a record that only remembers the folder name: `load` does
        not trust its (empty) bitfield, so a run that was killed during the recheck rechecks again."""
        with self._save_lock:
            if not self._closed:  # no descriptors to sync once closed
                self._sync_dirty()
            n = self.torrent.piece_count
            bits = bytearray((n + 7) // 8)
            for i in have:
                bits[i >> 3] |= 0x80 >> (i & 7)
            raw = {"v": VERSION, "root": self._root(), "have": bits.hex(), "files": self._stamp(), "checked": checked}
            tmp = self.record_path.with_name(f"{self.record_path.name}.{secrets.token_hex(6)}.tmp")
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            try:
                with os.fdopen(fd, "w") as f:
                    f.write(json.dumps(raw))
                os.replace(tmp, self.record_path)
            except BaseException:
                with contextlib.suppress(OSError):
                    os.unlink(tmp)
                raise

    def load(self) -> set[int] | None:
        """The saved bitfield, or None when the record is missing, unreadable or stale."""
        try:
            raw = json.loads(self.record_path.read_text())
            if raw["v"] != VERSION or raw["files"] != self._stamp() or raw.get("root", self._root()) != self._root():
                return None
            if raw.get("checked", True) is not True:
                return None
            bits = bytes.fromhex(raw["have"])
            n = self.torrent.piece_count
            if len(bits) != (n + 7) // 8 or (n % 8 and bits[-1] & (0xFF >> (n % 8))):
                return None
            return {i for i in range(n) if bits[i >> 3] & (0x80 >> (i & 7))}
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            return None


# --- not overwriting what is already in the output folder ---


def _recorded_root(dest: Path, torrent: Torrent, root: str) -> str | None:
    """The top-level name an earlier run of this torrent saved under, if its record says so and it is plausible."""
    try:
        recorded = json.loads((dest / f"{torrent.infohash.hex()}.anydm.json").read_text()).get("root")
    except (OSError, ValueError, AttributeError):
        return None
    if isinstance(recorded, str) and recorded == clean_name(recorded, "") and (
        recorded == root or recorded.startswith(f"{root} (")
    ):
        return recorded
    return None


def _usable(candidate: Torrent, dest: Path, cancel: threading.Event | None) -> bool:
    """True when saving `candidate` under `dest` overwrites nothing that is not part of this torrent."""
    paths = candidate.file_paths(dest)
    root = dest.resolve() / candidate.files[0].path[0]
    if not os.path.lexists(root):
        return True
    if any(len(f.path) > 1 for f in candidate.files) and (root.is_symlink() or not root.is_dir()):
        return False  # a file, or a link, where the torrent needs a folder
    existing = {p for p in paths if os.path.lexists(p)}
    if not existing:
        return True
    for p, f in zip(paths, [f for f in candidate.files if not f.pad], strict=True):
        if p in existing and (p.is_symlink() or not p.is_file() or p.stat().st_size > f.length):
            return False  # not a file of ours: too big to be a copy of this one
    probe = Storage(candidate, dest, create=False)
    try:
        for i in range(candidate.piece_count):
            if cancel is not None and cancel.is_set():
                raise RecheckCancelled
            if probe.piece_ok(i):
                return True  # holds this torrent's own data: a copy to repair or resume
        return False
    finally:
        probe.close()


def claim_layout(torrent: Torrent, dest: Path, cancel: threading.Event | None = None) -> Torrent:
    """The torrent under a top-level name where nothing foreign is in the way: its own name, or ` (1)`, ` (2)`...
    A folder an earlier run chose is remembered in the resume record. Files that already hold some of this
    torrent's pieces count as its own, so a partial copy is rechecked and resumed rather than left behind."""
    first = next((f for f in torrent.files if not f.pad), None)
    if first is None:
        return torrent
    root = first.path[0]
    recorded = _recorded_root(dest, torrent, root)
    if recorded is not None:
        return torrent if recorded == root else torrent.with_root(recorded)
    for n in range(MAX_ROOT_TRIES):
        name = root if n == 0 else f"{root} ({n})"
        candidate = torrent if n == 0 else torrent.with_root(name)
        if _usable(candidate, dest, cancel):
            return candidate
    raise DownloadError(f"cannot find a free name for {root!r} in {dest}")
