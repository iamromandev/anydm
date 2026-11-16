"""Fetch a torrent's info dictionary from peers (BEP 9 over the BEP 10 extension protocol).

Everything a peer sends is hostile. The size it claims is capped (16 MiB) and checked against what other peers
claim, every piece must have exactly the expected length, and the whole is accepted only when its SHA-1 is the
infohash. A peer that lies about the size, sends a piece of the wrong length, or is the only source of bytes that
fail the hash is banned. When the bytes of several peers were mixed and fail the hash, nobody is blamed at once:
the next attempt takes all pieces from a single peer, which then is either right or banned."""

import asyncio
import contextlib
import hashlib
import logging
import time
from collections.abc import Sequence

from src.core.names import clean_name
from src.core.progress import Progress, Reporter
from src.engine.torrent import peer
from src.engine.torrent.bencode import BencodeError, decode_prefix, encode
from src.engine.torrent.dht import DhtClient, dht_peers_loop
from src.engine.torrent.peer import Msg, PeerError
from src.engine.torrent.session import (
    ANNOUNCE_POLL,
    MAX_NEW_PEERS,
    MAX_PENDING,
    MIN_ANNOUNCE,
    TRACKER_RETRY,
    WAIT_NOTICE_EVERY,
    Addr,
    announce_delay,
    waiting_message,
)
from src.engine.torrent.tracker import AnnounceParams, TrackerError, announce_tiers

UT_METADATA = b"ut_metadata"
MAX_METADATA = 16 << 20
PIECE = 16384
REQUEST, DATA, REJECT = 0, 1, 2
MAX_SIZES = 3  # different sizes peers may claim at once; a newcomer with a further size evicts the weakest one
MAX_CONNECTIONS = 30
PIPELINE = 2  # outstanding piece requests per peer: few, so that several peers share the pieces
CONNECT_TIMEOUT = 10.0
REQUEST_TIMEOUT = 15.0  # the oldest unanswered request may wait this long
IDLE_LIMIT = 60.0  # a peer that has nothing to give us and nothing to ask for this long is dropped
MAX_JUNK = 32  # unsolicited or repeated pieces tolerated from one peer
OUR_ID = 1  # the extension id we ask peers to use when they send us ut_metadata messages
SOLO_MIN = 10.0  # seconds a solo attempt may take at least ...
SOLO_PER_PIECE = 2.0  # ... and this much per metadata piece: the whole attempt, however slowly the owner drips
MAX_SEEN_PEERS = 50  # peers kept for the download session that follows

_log = logging.getLogger(__name__)


def solo_deadline(count: int) -> float:
    """Seconds a peer alone may take to deliver all `count` pieces."""
    return max(SOLO_MIN, count * SOLO_PER_PIECE)


def _ext(ext_id: int, d: dict[bytes, int], data: bytes = b"") -> Msg:
    return Msg(peer.EXTENDED, bytes([ext_id]) + encode(d) + data)


def request_piece(ext_id: int, index: int) -> Msg:
    return _ext(ext_id, {b"msg_type": REQUEST, b"piece": index})


def reject_piece(ext_id: int, index: int) -> Msg:
    return _ext(ext_id, {b"msg_type": REJECT, b"piece": index})


def data_piece(ext_id: int, index: int, total: int, data: bytes) -> Msg:
    return _ext(ext_id, {b"msg_type": DATA, b"piece": index, b"total_size": total}, data)


def parse_message(body: bytes) -> tuple[int, int, int | None, bytes]:
    """(msg_type, piece, total_size, data) of a ut_metadata message body (what follows the extension id)."""
    try:
        d, end = decode_prefix(body, max_depth=4)
    except BencodeError as e:
        raise PeerError(f"bad metadata message: {e}") from None
    if not isinstance(d, dict):
        raise PeerError("bad metadata message")
    kind, index, total = d.get(b"msg_type"), d.get(b"piece"), d.get(b"total_size")
    if not isinstance(kind, int) or not isinstance(index, int) or isinstance(index, bool) or index < 0:
        raise PeerError("bad metadata message")
    return kind, index, total if isinstance(total, int) and not isinstance(total, bool) else None, body[end:]


class _Buf:
    """The pieces collected for one claimed size."""

    def __init__(self, size: int) -> None:
        self.size = size
        self.count = -(-size // PIECE)
        self.chunks: dict[int, tuple[bytes, Addr]] = {}  # piece -> (data, who sent it)
        self.inflight: dict[int, Addr] = {}
        self.solo = False  # after a mixed failure: one peer at a time, so a bad one can be told apart
        self.owner: Addr | None = None
        self.deadline = 0.0  # when the owner of a solo attempt must be done
        self.peers: set[Addr] = set()  # who claimed this size and is still connected
        self.progress_at = time.monotonic()  # when it was made or last got a piece

    def piece_size(self, index: int) -> int:
        return min(PIECE, self.size - index * PIECE)

    def reset(self) -> None:
        self.chunks.clear()
        self.inflight.clear()


class _Peer:
    def __init__(self, addr: Addr, writer: asyncio.StreamWriter) -> None:
        self.addr, self.writer = addr, writer
        self.ext_id = 0
        self.buf: _Buf | None = None
        self.mine: dict[int, float] = {}  # pieces asked of this peer -> when
        self.rejected: set[int] = set()
        self.junk = 0
        self.ready = False
        self.last_progress = time.monotonic()

    def send(self, msg: Msg) -> None:
        if not self.writer.is_closing():
            self.writer.write(peer.encode_message(msg))


class MetadataFetcher:
    def __init__(
        self,
        infohash: bytes,
        peer_id: bytes,
        report: Reporter,
        name: str | None = None,
        tiers: Sequence[Sequence[str]] = (),
        dht: DhtClient | None = None,
        extra_peers: Sequence[Addr] = (),
        port: int = 6881,
        connect_timeout: float = CONNECT_TIMEOUT,
    ) -> None:
        self.infohash, self.peer_id, self.report = infohash, peer_id, report
        self.name = clean_name(name) if name else infohash.hex()
        self.tiers, self.dht, self.port = [list(t) for t in tiers], dht, port
        self.banned: set[Addr] = set()
        self.peers_seen: dict[Addr, None] = {}  # peers that spoke ut_metadata: worth keeping for the download itself
        self._extra = list(extra_peers)
        self._connect_timeout = connect_timeout
        self._bufs: dict[int, _Buf] = {}
        self._result: asyncio.Future[bytes] = asyncio.get_running_loop().create_future()
        self._tasks: dict[Addr, asyncio.Task[None]] = {}
        self._slots = asyncio.Semaphore(MAX_CONNECTIONS)
        self._active = 0
        self._tried = 0
        self._noticed: set[str] = set()

    # --- public ---

    async def run(self) -> bytes:
        """The verified info dictionary. Never returns while no peer can supply it; the caller cancels."""
        loops = [
            asyncio.create_task(self._tracker_loop()),
            asyncio.create_task(self._progress_loop()),
            asyncio.create_task(self._wait_notice_loop()),
        ]
        if self.dht is not None:
            loops.append(asyncio.create_task(dht_peers_loop(self.dht, self.infohash, self.add_peers, self.report)))
        self.add_peers(self._extra)
        try:
            return await self._result
        finally:
            everything = [*loops, *self._tasks.values()]
            for t in everything:
                t.cancel()
            await asyncio.gather(*everything, return_exceptions=True)

    def add_peers(self, addrs: Sequence[Addr]) -> None:
        added = 0
        for addr in dict.fromkeys(addrs):
            if added >= MAX_NEW_PEERS or len(self._tasks) >= MAX_PENDING:
                break
            if addr in self._tasks or addr in self.banned or not (0 < addr[1] < 65536):
                continue
            task = asyncio.create_task(self._peer_task(addr))
            self._tasks[addr] = task
            task.add_done_callback(lambda t, a=addr: self._forget(a, t))
            added += 1

    def _forget(self, addr: Addr, task: asyncio.Task[None]) -> None:
        if self._tasks.get(addr) is task:
            del self._tasks[addr]

    # --- finding peers ---

    def _notice_once(self, msg: str) -> None:
        if msg not in self._noticed:
            self._noticed.add(msg)
            self.report.notice(msg)

    async def _tracker_loop(self) -> None:
        if not self.tiers:
            return
        event: str | None = "started"
        while True:
            delay = soon = TRACKER_RETRY
            try:
                params = AnnounceParams(self.infohash, self.peer_id, self.port, 0, 0, 1)
                res = await announce_tiers(self.tiers, params, event)
            except Exception as e:  # a TrackerError's text is scrubbed of URLs; any other type could carry anything
                self._notice_once(f"tracker: {e if isinstance(e, TrackerError) else type(e).__name__}")
            else:
                event = None
                self.add_peers(res.peers)
                delay = announce_delay(res.interval, res.min_interval)
                soon = max(MIN_ANNOUNCE, float(res.min_interval))
            await self._wait_to_announce(delay, soon)

    async def _wait_to_announce(self, delay: float, soon: float) -> None:
        """Sleep until the next announce is due, but ask again after `soon` while no peer is connected: waiting out
        a tracker's half-hour interval with nobody to fetch from helps no one."""
        start = time.monotonic()
        while True:
            elapsed = time.monotonic() - start
            if elapsed >= delay or (elapsed >= soon and self._active == 0):
                return
            await asyncio.sleep(min(max(delay, 0.0) - elapsed, ANNOUNCE_POLL, max(soon - elapsed, 0.01)))

    async def _wait_notice_loop(self) -> None:
        while True:
            await asyncio.sleep(WAIT_NOTICE_EVERY)
            if self._active == 0:
                trackers = sum(len(t) for t in self.tiers)
                self.report.notice(waiting_message(trackers, self.dht is not None, self._tried, "metadata"))

    async def _progress_loop(self) -> None:
        while True:
            self._progress()
            await asyncio.sleep(0.25)

    def _progress(self) -> None:
        best = max(self._bufs.values(), key=lambda b: len(b.chunks), default=None)
        done = sum(len(d) for d, _ in best.chunks.values()) if best else 0
        total = best.size if best else None
        self.report.update(Progress(done, total, 0.0, phase="metadata", peers=self._active, name=self.name))

    # --- one peer ---

    def _ban(self, addr: Addr) -> None:
        self.banned.add(addr)
        task = self._tasks.get(addr)
        if task is not None and task is not asyncio.current_task():
            task.cancel()

    async def _peer_task(self, addr: Addr) -> None:
        async with self._slots:
            self._tried += 1
            writer: asyncio.StreamWriter | None = None
            p: _Peer | None = None
            try:
                async with asyncio.timeout(self._connect_timeout):
                    reader, writer = await asyncio.open_connection(*addr)
                    _, reserved = await peer.handshake(reader, writer, self.infohash, self.peer_id)
                if not reserved[5] & 0x10:
                    raise PeerError("peer does not speak the extension protocol")
                p = _Peer(addr, writer)
                p.send(peer.extension_handshake({UT_METADATA: OUR_ID}))
                await self._serve(p, reader)
            except (PeerError, OSError, TimeoutError, ValueError, UnicodeError):
                pass
            except Exception:  # a bug or a surprise: this peer is dropped, nothing else is affected
                _log.debug("metadata peer dropped after an unexpected error", exc_info=True)
            finally:
                if p is not None:
                    self._release(p)
                if writer is not None:
                    writer.close()

    def _release(self, p: _Peer) -> None:
        if p.ready:
            self._active -= 1
            p.ready = False
        buf = p.buf
        if buf is None:
            return
        current = self._bufs.get(buf.size) is buf  # false when it was evicted: its peers are cut off, not at fault
        buf.peers.discard(p.addr)
        for i in p.mine:
            if buf.inflight.get(i) == p.addr:
                del buf.inflight[i]
        if buf.solo and buf.owner == p.addr:
            if current and not self._result.done():
                self.banned.add(p.addr)  # it left, or was thrown out, while holding the only slot
            buf.owner = None
            buf.reset()  # what a half-way owner sent is not worth mixing with the next one's
        if not buf.peers and current:
            del self._bufs[buf.size]  # nobody is left to vouch for this size, whatever it collected

    async def _serve(self, p: _Peer, reader: asyncio.StreamReader) -> None:
        reading: asyncio.Task[Msg] | None = None
        try:
            while not self._result.done():
                if reading is None:
                    reading = asyncio.create_task(peer.read_message(reader))
                await asyncio.wait({reading}, timeout=self._poll_time(p))  # never cancel a read: it would lose bytes
                now = time.monotonic()
                if p.buf is not None and p.buf.solo and p.buf.owner == p.addr and now > p.buf.deadline:
                    self._ban(p.addr)
                    raise PeerError("peer took too long to deliver the metadata alone")
                if reading.done():
                    msg, reading = reading.result(), None
                    self._on_message(p, msg)
                if p.mine and now - min(p.mine.values()) > REQUEST_TIMEOUT:
                    raise PeerError("peer ignored our requests")
                if now - p.last_progress > IDLE_LIMIT and not p.mine:
                    raise PeerError("peer has nothing for us")
                self._fill(p)
                with contextlib.suppress(ConnectionError):
                    async with asyncio.timeout(REQUEST_TIMEOUT):
                        await p.writer.drain()
        finally:
            if reading is not None:
                if reading.done() and not reading.cancelled():
                    reading.exception()  # mark it retrieved
                reading.cancel()

    @staticmethod
    def _poll_time(p: _Peer) -> float:
        """How long to wait for the next message: a second, or less when the solo deadline is nearer."""
        buf = p.buf
        if buf is not None and buf.solo and buf.owner == p.addr:
            return min(1.0, max(0.0, buf.deadline - time.monotonic()) + 0.01)
        return 1.0

    def _buf_for(self, size: int) -> _Buf:
        buf = self._bufs.get(size)
        if buf is None:
            if len(self._bufs) >= MAX_SIZES:
                self._evict_weakest()
            buf = self._bufs[size] = _Buf(size)
        return buf

    def _evict_weakest(self) -> None:
        """Make room for a new size: drop the one with the fewest peers, and of those the one that went longest
        without a piece. Made-up sizes come with few peers and no progress, so they go first. Its peers are
        disconnected (not banned: they may be honest and merely outvoted)."""
        victim = min(self._bufs.values(), key=lambda b: (len(b.peers), b.progress_at))
        del self._bufs[victim.size]
        for addr in victim.peers:
            task = self._tasks.get(addr)
            if task is not None and task is not asyncio.current_task():
                task.cancel()

    def _on_message(self, p: _Peer, m: Msg) -> None:
        if m.id != peer.EXTENDED or not m.payload:
            return  # we are not downloading pieces yet: choke, bitfield, have and the rest do not matter
        if m.payload[0] == 0:
            self._on_handshake(p, m.payload)
        elif m.payload[0] == OUR_ID and p.buf is not None:
            self._on_metadata(p, p.buf, m.payload[1:])
        # anything else is an extension we did not offer

    def _on_handshake(self, p: _Peer, payload: bytes) -> None:
        if p.buf is not None:
            return  # a second handshake changes nothing
        exts, size = peer.parse_extension_handshake(payload)
        ext_id = exts.get(UT_METADATA, 0)
        if not 0 < ext_id < 256 or size is None:
            raise PeerError("peer has no metadata to give")
        if not 0 < size <= MAX_METADATA:
            self._ban(p.addr)
            raise PeerError("peer claims a metadata size outside the limit")
        p.ext_id, p.buf = ext_id, self._buf_for(size)
        p.buf.peers.add(p.addr)
        self.peers_seen[p.addr] = None
        if len(self.peers_seen) > MAX_SEEN_PEERS:
            del self.peers_seen[next(iter(self.peers_seen))]
        p.ready = True
        p.last_progress = time.monotonic()
        self._active += 1

    def _fill(self, p: _Peer) -> None:
        buf = p.buf
        if buf is None:
            return
        now = time.monotonic()
        for i in range(buf.count):
            if len(p.mine) >= PIPELINE:
                return
            if i in buf.chunks or i in buf.inflight or i in p.rejected or i in p.mine:
                continue
            if buf.solo:
                if buf.owner not in (None, p.addr):
                    return  # someone else is being tried alone
                if buf.owner is None:
                    buf.owner, buf.deadline = p.addr, now + solo_deadline(buf.count)
            buf.inflight[i] = p.addr
            p.mine[i] = now
            p.send(request_piece(p.ext_id, i))

    def _on_metadata(self, p: _Peer, buf: _Buf, body: bytes) -> None:
        kind, index, total, data = parse_message(body)
        if kind == REJECT:
            if index not in p.mine:
                self._junk(p)  # a REJECT for something we never asked for
                return
            del p.mine[index]
            if buf.inflight.get(index) == p.addr:
                del buf.inflight[index]
            p.rejected.add(index)
            if buf.solo and buf.owner == p.addr:
                self._ban(p.addr)  # it cannot deliver the whole, and holds the only slot
                raise PeerError("peer rejected a piece of the solo attempt")
            return
        if kind != DATA:
            p.send(reject_piece(p.ext_id, index))  # a request from them: we have nothing to serve (BEP 9)
            self._junk(p)
            return
        if index not in p.mine:
            self._junk(p)
            return
        if index >= buf.count or (total is not None and total != buf.size) or len(data) != buf.piece_size(index):
            self._ban(p.addr)
            raise PeerError("peer sent a metadata piece that does not fit")
        del p.mine[index]
        if buf.inflight.get(index) == p.addr:
            del buf.inflight[index]
        p.last_progress = buf.progress_at = time.monotonic()
        if buf.solo and buf.owner != p.addr:
            return  # answered an old request from before the solo attempt
        buf.chunks.setdefault(index, (data, p.addr))
        if len(buf.chunks) == buf.count:
            self._verify(p, buf)

    @staticmethod
    def _junk(p: _Peer) -> None:
        """Count a message that does nothing for us (unasked pieces or rejects, requests); too many end the connection."""
        p.junk += 1
        if p.junk > MAX_JUNK:
            raise PeerError("peer sends us messages we did not ask for")

    def _verify(self, p: _Peer, buf: _Buf) -> None:
        blob = b"".join(buf.chunks[i][0] for i in range(buf.count))
        if hashlib.sha1(blob).digest() == self.infohash:
            buf.solo = False
            self._progress()
            if not self._result.done():
                self._result.set_result(blob)
            return
        senders = {addr for _, addr in buf.chunks.values()}
        buf.reset()
        if len(senders) == 1:
            culprit = next(iter(senders))
            self._ban(culprit)
            buf.owner = None
            if culprit == p.addr:
                raise PeerError("peer sent metadata that does not match the infohash")
        else:
            buf.solo, buf.owner = True, None  # the next try takes everything from one peer
