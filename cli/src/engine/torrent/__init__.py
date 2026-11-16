"""Pure-Python BitTorrent client. Loaded lazily by `engine_for`; nothing here may be imported by `src.main`."""

import asyncio
import contextlib
import errno
import os
import secrets
import stat
import threading
import time
from collections.abc import Awaitable, Callable
from dataclasses import replace
from pathlib import Path

import httpx

from src.core.errors import DownloadError
from src.core.progress import Progress, Reporter
from src.engine import Job
from src.engine.http.engine import _Busy, _Lock  # the same flock helper the HTTP engine uses
from src.engine.torrent.dht import ENV_BOOTSTRAP, DhtClient, bootstrap_from_env
from src.engine.torrent.magnet import Magnet, parse_magnet
from src.engine.torrent.metadata import MAX_METADATA, MetadataFetcher
from src.engine.torrent.metainfo import MetainfoError, Torrent, parse
from src.engine.torrent.seed import MAX_UNCHOKED, Seeder
from src.engine.torrent.session import MAX_ANNOUNCE, MIN_ANNOUNCE, Addr, Session
from src.engine.torrent.storage import Storage, claim_layout
from src.engine.torrent.tracker import AnnounceParams, announce_tiers

MAX_TORRENT_BYTES = 10 << 20
LISTEN_PORT = 6881
SEED_MAX_PEERS = 50
SEED_PROGRESS_EVERY = 0.5
SEED_STOP_TIMEOUT = 0.8  # the `stopped` announce gets this long, alongside stopping the seeder, so Ctrl-C ends within a second
NOT_LISTENING = 0  # the port announced while nothing listens (the download phase); the seeder announces its real one
SEED_RETRY = 60.0  # between announces after a failed one


def _peer_id() -> bytes:
    return b"-AD0001-" + secrets.token_hex(6).encode()


async def _load(job: Job) -> bytes:
    """The bytes of the .torrent: a local file or an http(s) URL, at most 10 MiB."""
    if job.source.startswith(("http://", "https://")):
        try:
            async with (
                httpx.AsyncClient(verify=not job.insecure, follow_redirects=True, max_redirects=10, timeout=30.0) as c,
                c.stream("GET", job.source) as resp,
            ):
                resp.raise_for_status()
                body = bytearray()
                async for chunk in resp.aiter_bytes():
                    body += chunk
                    if len(body) > MAX_TORRENT_BYTES:
                        raise DownloadError("the .torrent file is larger than 10 MiB")
                return bytes(body)
        except httpx.HTTPStatusError as e:
            raise DownloadError(f"HTTP {e.response.status_code} fetching the .torrent file") from e
        except httpx.HTTPError as e:
            raise DownloadError(f"cannot fetch the .torrent file: {e}") from e
    path = Path(job.source).expanduser()
    try:
        if path.stat().st_size > MAX_TORRENT_BYTES:
            raise DownloadError("the .torrent file is larger than 10 MiB")
        return path.read_bytes()
    except OSError as e:
        raise DownloadError(f"cannot read {path}: {e.strerror}") from e


async def _in_thread[T](work: Callable[[threading.Event], T]) -> T:
    """Run slow disk work in a thread. When cancelled (Ctrl-C), tell it to stop and wait for it, so it is
    never still reading when the files are closed."""
    cancel = threading.Event()
    fut = asyncio.ensure_future(asyncio.to_thread(work, cancel))
    try:
        return await asyncio.shield(fut)
    except asyncio.CancelledError:
        cancel.set()
        await asyncio.wait({fut})
        if not fut.cancelled():
            fut.exception()  # it ends with RecheckCancelled; that is expected
        raise


def _dht(report: Reporter) -> DhtClient | None:
    """A DHT client on the bootstrap nodes from the environment (or the built-in ones); None when the DHT is off:
    the variable is set to nothing, or to something with no valid host:port (which is said, since that is a typo)."""
    raw = os.environ.get(ENV_BOOTSTRAP)
    if raw is None:
        return DhtClient()
    nodes = bootstrap_from_env()
    if not nodes:
        if raw != "":
            report.notice(f"{ENV_BOOTSTRAP} has no valid host:port; DHT off")
        return None
    return DhtClient(nodes)


def _cache_path(dest: Path, infohash: bytes) -> Path:
    return dest / f"{infohash.hex()}.torrent"


MAX_CACHE_BYTES = MAX_METADATA + 4096  # the metadata plus the little around it in the .torrent we write


class _SuspectCache(Exception):
    """The cache path holds something that is not a regular file of a sane size."""


def _read_cache_file(path: Path, infohash: bytes, cancel: threading.Event) -> Torrent | None:
    """Runs in a thread. The file is opened without following a link and without blocking (a FIFO would otherwise
    hang us), must be a regular file, and at most MAX_CACHE_BYTES of it are read."""
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_NOCTTY", 0)
    try:
        fd = os.open(path, flags)
    except FileNotFoundError:
        return None
    except OSError as e:
        if e.errno in (errno.ELOOP, errno.EMLINK):  # what O_NOFOLLOW says about a symbolic link
            raise _SuspectCache from None
        return None
    with os.fdopen(fd, "rb", buffering=0) as fh:
        st = os.fstat(fh.fileno())
        if not stat.S_ISREG(st.st_mode) or st.st_size > MAX_CACHE_BYTES:
            raise _SuspectCache
        data = bytearray()
        while len(data) <= MAX_CACHE_BYTES and not cancel.is_set():
            chunk = fh.read(min(1 << 20, MAX_CACHE_BYTES + 1 - len(data)))
            if not chunk:
                break
            data += chunk
    if len(data) > MAX_CACHE_BYTES:
        raise _SuspectCache
    try:
        torrent = parse(bytes(data))
    except MetainfoError:
        return None
    return torrent if torrent.infohash == infohash else None


async def _read_cache(dest: Path, infohash: bytes, report: Reporter) -> Torrent | None:
    """The metadata kept from an earlier run of this magnet, or None when there is none or it cannot be trusted:
    the file is parsed like any .torrent and must have this very infohash. Reading and parsing happen in a thread
    so a slow disk or a big file never stalls the event loop (or Ctrl-C)."""
    path = _cache_path(dest, infohash)
    try:
        return await _in_thread(lambda cancel: _read_cache_file(path, infohash, cancel))
    except _SuspectCache:
        report.notice("ignoring the cached metadata file: it is not a regular file of a sane size; fetching it again")
        return None


def _write_cache(dest: Path, infohash: bytes, raw: bytes) -> None:
    """Keep the metadata next to the download (`<infohash>.torrent`), through a temporary file so a kill never
    leaves half of one. Best effort: without it the next run just fetches the metadata again."""
    path = _cache_path(dest, infohash)
    tmp = path.with_name(path.name + ".tmp")
    with contextlib.suppress(OSError):
        tmp.unlink(missing_ok=True)
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o644)
        with os.fdopen(fd, "wb") as fh:
            fh.write(raw)
        tmp.replace(path)


async def _magnet_torrent(dest: Path, magnet: Magnet, report: Reporter, dht: DhtClient | None) -> tuple[Torrent, list[Addr]]:
    """The torrent a magnet stands for: from the cache in `dest`, or fetched from peers (the metadata phase),
    and the peers that gave the metadata, which the download starts with."""
    torrent = await _read_cache(dest, magnet.infohash, report)
    peers: list[Addr] = []
    if torrent is None:
        fetcher = MetadataFetcher(
            magnet.infohash,
            _peer_id(),
            report,
            name=magnet.name,
            tiers=[magnet.trackers] if magnet.trackers else [],
            dht=dht,
            port=NOT_LISTENING,
        )
        info = await fetcher.run()
        peers = [a for a in fetcher.peers_seen if a not in fetcher.banned]
        raw = b"d4:info" + info + b"e"  # the info bytes as they were hashed, so the infohash stays what the magnet said
        torrent = parse(raw)
        _write_cache(dest, magnet.infohash, raw)
    return replace(torrent, tiers=[list(magnet.trackers)] if magnet.trackers else []), peers


async def _seed_announces(torrent: Torrent, seeder: Seeder, peer_id: bytes) -> None:
    """Tell the trackers we are here to serve (`started`, then every interval), so leechers can find us."""
    if not torrent.tiers:
        return
    event: str | None = "started"
    while True:
        delay = SEED_RETRY
        params = AnnounceParams(torrent.infohash, peer_id, seeder.port, seeder.uploaded, 0, 0)
        with contextlib.suppress(Exception):  # a tracker that fails or lies must not stop the seeding
            res = await announce_tiers(torrent.tiers, params, event)
            event = None
            delay = min(max(res.interval, res.min_interval, MIN_ANNOUNCE), MAX_ANNOUNCE)
        await asyncio.sleep(delay)


async def _announce_stopped(torrent: Torrent, seeder: Seeder, peer_id: bytes) -> None:
    """Best effort and quick: say goodbye to the trackers, giving up after SEED_STOP_TIMEOUT."""
    if not torrent.tiers:
        return
    params = AnnounceParams(torrent.infohash, peer_id, seeder.port, seeder.uploaded, 0, 0)
    with contextlib.suppress(Exception):
        await asyncio.wait_for(
            announce_tiers(torrent.tiers, params, "stopped", timeout=SEED_STOP_TIMEOUT, overall=SEED_STOP_TIMEOUT),
            SEED_STOP_TIMEOUT,
        )


async def _seed(torrent: Torrent, storage: Storage, report: Reporter, peer_id: bytes) -> None:
    """After the download: serve the finished files until the user cancels (Ctrl-C), which ends it normally."""
    seeder = Seeder(
        torrent,
        storage,
        set(range(torrent.piece_count)),
        peer_id,
        host="0.0.0.0",
        port=LISTEN_PORT,
        max_peers=SEED_MAX_PEERS,
        max_unchoked=MAX_UNCHOKED,
        info=torrent.info or None,  # so a peer that has only a magnet link can get the metadata from us
    )
    announcer: asyncio.Task[None] | None = None
    try:
        try:
            await seeder.start()
        except OSError:  # the usual port is taken (another client, or another anydm): any free one will do
            seeder.port = 0
            await seeder.start()
        announcer = asyncio.create_task(_seed_announces(torrent, seeder, peer_id))
        last_t, last_up, speed = time.monotonic(), seeder.uploaded, 0.0
        while True:
            report.update(Progress(torrent.total, torrent.total, speed, phase="seeding", peers=seeder.peer_count, name=torrent.name))
            await asyncio.sleep(SEED_PROGRESS_EVERY)
            now = time.monotonic()
            inst = (seeder.uploaded - last_up) / max(now - last_t, 1e-6)
            speed = inst if speed == 0 else speed * 0.7 + inst * 0.3
            last_t, last_up = now, seeder.uploaded
    except asyncio.CancelledError:
        task = asyncio.current_task()
        if task is None or task.uncancel() > 0:
            raise  # somebody else's cancel is still pending; Ctrl-C, the one request we expect, is not
    finally:
        work: list[Awaitable[object]] = [seeder.stop()]  # stopping and saying goodbye happen together, so the exit is quick
        if announcer is not None:
            announcer.cancel()
            work += [announcer, _announce_stopped(torrent, seeder, peer_id)]
        await asyncio.gather(*work, return_exceptions=True)
        report.update(Progress(torrent.total, torrent.total, 0.0, phase="seeding", peers=0, name=torrent.name))


class TorrentEngine:
    async def run(self, job: Job, report: Reporter) -> list[Path]:
        magnet = parse_magnet(job.source) if job.source.lower().startswith("magnet:") else None
        torrent = None
        if magnet is None:
            try:
                torrent = parse(await _load(job))
            except MetainfoError as e:
                raise DownloadError(str(e)) from e
        try:
            return await self._download(job, torrent, magnet, report)
        except MetainfoError as e:
            raise DownloadError(str(e)) from e
        except OSError as e:
            reason = "disk full" if e.errno == errno.ENOSPC else (e.strerror or str(e))
            raise DownloadError(f"{reason}; partial data kept") from e

    async def _download(self, job: Job, torrent: Torrent | None, magnet: Magnet | None, report: Reporter) -> list[Path]:
        dest = job.dest_dir
        infohash = torrent.infohash if torrent else magnet.infohash if magnet else b""
        lock = _Lock(dest / f"{infohash.hex()}.anydm.lock")
        try:
            lock.acquire()
        except _Busy as e:
            raise DownloadError("already in progress: another anydm is downloading this torrent here") from e
        dht = _dht(report)
        peer_id = _peer_id()
        try:
            known: list[Addr] = []
            if torrent is None:
                assert magnet is not None
                torrent, known = await _magnet_torrent(dest, magnet, report, dht)
            original = torrent
            torrent = await _in_thread(lambda cancel: claim_layout(original, dest, cancel))
            if torrent is not original:
                old, new = original.file_paths(dest)[0].relative_to(dest.resolve()).parts[0], torrent.files[0].path[0]
                report.notice(f"{old!r} in {dest} is not part of this torrent; saving as {new!r} so nothing is overwritten")
            storage = Storage(torrent, dest)
            try:
                have = storage.load()
                if have is None:
                    storage.save(set(), checked=False)  # remembers the folder name even if this run is killed

                    def checking(n: int) -> None:
                        done = min(n * torrent.piece_length, torrent.total)
                        report.update(Progress(done, torrent.total, 0.0, phase="checking", name=torrent.name))

                    have = await _in_thread(lambda cancel: storage.recheck(checking, cancel))
                    storage.save(have)
                if have:
                    word = "all" if len(have) == torrent.piece_count else f"{len(have)} of {torrent.piece_count}"
                    report.notice(f"{word} pieces already complete")
                session = Session(
                    torrent,
                    storage,
                    have,
                    report,
                    peer_id,
                    NOT_LISTENING,
                    extra_peers=known,
                    dht=dht,
                    dht_always=magnet is not None,
                )
                await session.run()
                if job.seed:
                    await _seed(torrent, storage, report, peer_id)
            finally:
                storage.close()
        finally:
            if dht is not None:
                dht.close()
            lock.release()
        return torrent.file_paths(dest)
