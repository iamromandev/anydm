"""One download: find peers through the trackers and the DHT, fetch pieces from up to 50 of them, verify, store, report.

Everything a tracker or a peer sends is hostile: addresses are validated and capped, messages are checked
against the protocol, requests that go unanswered cost the peer its connection, and a peer that sends a bad
piece is banned. With no peers and no answering tracker or DHT the session waits, with a notice every 30 s, until the
user interrupts it."""

import asyncio
import contextlib
import hashlib
import random
import time
from dataclasses import dataclass, field

from src.core.errors import DownloadError
from src.core.progress import Progress, Reporter
from src.engine.torrent import peer
from src.engine.torrent.dht import DhtClient, dht_peers_loop
from src.engine.torrent.metainfo import Torrent
from src.engine.torrent.peer import PeerError
from src.engine.torrent.picker import Picker
from src.engine.torrent.storage import Storage
from src.engine.torrent.tracker import AnnounceParams, TrackerError, announce_tiers

MAX_PEERS = 50
PIPELINE = 5  # outstanding block requests per peer
CONNECT_TIMEOUT = 10.0
REQUEST_TIMEOUT = 30.0  # the oldest unanswered request may wait this long, whatever else the peer sends
IDLE_LIMIT = 150.0  # a peer that delivers no block and does not unchoke us for this long is dropped
KEEPALIVE_EVERY = 60.0
SAVE_EVERY = 5.0
MIN_ANNOUNCE = 60.0  # never announce more often than this, whatever the tracker asks for
MAX_ANNOUNCE = 3600.0
TRACKER_RETRY = 30.0  # after every tracker failed
LOW_PEERS = 5  # fewer than this and we ask again before the interval is up
ANNOUNCE_POLL = 5.0
MAX_NEW_PEERS = 200  # candidates taken from one announce
MAX_PENDING = 250  # addresses being dialled, queued or connected
WAIT_NOTICE_EVERY = 30.0
COMPLETED_TIMEOUT = 3.0
STOP_TIMEOUT = 2.0
HASH_OFFLOAD = 1 << 20  # pieces this big are hashed and written off the event loop

type Addr = tuple[str, int]


def announce_delay(interval: int, min_interval: int = 0) -> float:
    """Seconds to the next announce: the tracker's interval, but not under its minimum or MIN_ANNOUNCE."""
    return max(MIN_ANNOUNCE, float(min_interval), min(float(interval), MAX_ANNOUNCE))


def waiting_message(trackers: int, dht: bool, tried: int, what: str = "") -> str:
    """The notice while no peer is connected: where we are looking and how many peers were tried."""
    sources = [f"{trackers} trackers", *(["DHT"] if dht else []), f"{tried} peers tried"]
    return f"waiting for peers{f' for {what}' if what else ''} ({', '.join(sources)})"


def _sha_ok(blocks: list[bytes | None], expected: bytes) -> bool:
    h = hashlib.sha1()
    for b in blocks:
        h.update(b or b"")
    return h.digest() == expected


@dataclass
class _Work:
    index: int
    size: int
    blocks: list[bytes | None]  # filled as blocks arrive: nothing is allocated for a piece before its data is here
    next_begin: int = 0
    pending: dict[int, float] = field(default_factory=dict)  # begin -> when it was requested
    got: int = 0


class _Conn:
    """What we know about one connected peer."""

    def __init__(self, addr: Addr, writer: asyncio.StreamWriter) -> None:
        self.addr, self.writer = addr, writer
        self.has: set[int] = set()
        self.works: list[_Work] = []
        self.choked = True
        self.interested = False
        self.needed = 0  # pieces it has that we still lack
        self.seen_have = False  # a bitfield is only valid before any HAVE or bitfield (BEP 3)
        self.last_progress = time.monotonic()
        self.last_send = time.monotonic()

    def send(self, msg: peer.Msg) -> None:
        if not self.writer.is_closing():
            self.writer.write(peer.encode_message(msg))
            self.last_send = time.monotonic()

    def set_interested(self, want: bool) -> None:
        if want != self.interested:
            self.interested = want
            self.send(peer.Msg(peer.INTERESTED if want else peer.NOT_INTERESTED))

    def outstanding(self) -> int:
        return sum(len(w.pending) for w in self.works)

    def oldest_request(self) -> float | None:
        times = [t for w in self.works for t in w.pending.values()]
        return min(times) if times else None


class Session:
    def __init__(
        self,
        torrent: Torrent,
        storage: Storage,
        have: set[int],
        report: Reporter,
        peer_id: bytes,
        port: int = 6881,
        extra_peers: list[Addr] | None = None,
        connect_timeout: float = CONNECT_TIMEOUT,
        dht: DhtClient | None = None,
        dht_always: bool = False,
    ) -> None:
        self.torrent, self.storage, self.report, self.peer_id, self.port = torrent, storage, report, peer_id, port
        self.picker = Picker(torrent.piece_count, have)
        self.pieces_downloaded = 0
        self._done_bytes = sum(torrent.piece_size(i) for i in self.picker.have)
        self._extra_peers = extra_peers or []
        self.dht = None if torrent.private else dht  # BEP 27: a private torrent is only for its trackers
        self._dht_always = dht_always
        self._trackers_dead = asyncio.Event()  # no tracker at all, or the last announce found every one failing
        self._connect_timeout = connect_timeout
        self._active = 0
        self._tried = 0
        self._received = 0
        self._tasks: set[asyncio.Task[None]] = set()
        self._by_addr: dict[Addr, set[asyncio.Task[None]]] = {}  # every address being dialled or connected
        self._conns: set[_Conn] = set()
        self._fatal: DownloadError | None = None
        self._banned: set[Addr] = set()
        self._slots = asyncio.Semaphore(MAX_PEERS)
        self._done = asyncio.Event()
        self._noticed: set[str] = set()
        self._announced = False  # a tracker has accepted our `started`
        self._saving: asyncio.Future[str | None] | None = None
        if self.picker.finished:
            self._done.set()

    # --- public ---

    async def run(self) -> None:
        """Returns when every piece is verified and stored. Saves the resume record on the way out, also when
        cancelled, and tells the trackers when it finishes (`completed`, if this run downloaded anything) and
        when it exits (`stopped`)."""
        loops = [
            asyncio.create_task(self._tracker_loop()),
            asyncio.create_task(self._progress_loop()),
            asyncio.create_task(self._save_loop()),
            asyncio.create_task(self._wait_notice_loop()),
            asyncio.create_task(self._dht_loop()),
        ]
        try:
            try:
                await self._done.wait()
            finally:
                await self._shutdown(loops)
            if self._fatal is not None:
                raise self._fatal
            if self.pieces_downloaded > 0:
                await self._announce_quietly("completed", COMPLETED_TIMEOUT)
        finally:
            if self._announced:
                await self._announce_quietly("stopped", STOP_TIMEOUT)

    async def _shutdown(self, loops: list[asyncio.Task[None]]) -> None:
        for t in [*loops, *self._tasks]:
            t.cancel()
        await asyncio.gather(*loops, *self._tasks, return_exceptions=True)
        if self._saving is not None and not self._saving.done():
            await asyncio.wait({self._saving})  # a periodic save is still writing: let it finish first
        if problem := self._save_quietly(self.picker.have):
            self._notice_once(problem)
        self._progress(force_speed=0.0)

    async def _announce_quietly(self, event: str, timeout: float) -> None:
        """Best effort: a tracker that fails, lies or hangs must not turn a finished download into an error."""
        if not self.torrent.tiers:
            return
        with contextlib.suppress(Exception):
            await asyncio.wait_for(
                announce_tiers(self.torrent.tiers, self._params(), event, timeout=timeout, overall=timeout),
                timeout + 1.0,
            )

    # --- progress, saving ---

    def _progress(self, force_speed: float | None = None, speed: float = 0.0) -> None:
        self.report.update(
            Progress(
                done=self._done_bytes,
                total=self.torrent.total,
                speed=speed if force_speed is None else force_speed,
                phase="downloading",
                peers=self._active,
                name=self.torrent.name,
            )
        )

    async def _progress_loop(self) -> None:
        last_t, last_n, speed = time.monotonic(), self._received, 0.0
        while True:
            await asyncio.sleep(0.25)
            now = time.monotonic()
            inst = (self._received - last_n) / max(now - last_t, 1e-6)
            speed = inst if speed == 0 else speed * 0.7 + inst * 0.3
            last_t, last_n = now, self._received
            self._progress(speed=speed)

    def _save_quietly(self, have: set[int]) -> str | None:
        """Save the resume record. A failure is not worth stopping for (the next run just rechecks), and
        must never replace the reason the session is ending."""
        try:
            self.storage.save(have)
        except Exception as e:
            return f"could not save the resume record: {getattr(e, 'strerror', None) or type(e).__name__}"
        return None

    async def _save_loop(self) -> None:
        while True:
            await asyncio.sleep(SAVE_EVERY)
            self._saving = asyncio.ensure_future(asyncio.to_thread(self._save_quietly, self.picker.have))
            problem = await asyncio.shield(self._saving)  # cancelling this loop leaves the save to finish
            if problem:
                self._notice_once(problem)

    # --- finding peers ---

    def _params(self) -> AnnounceParams:
        left = self.torrent.total - self._done_bytes
        return AnnounceParams(self.torrent.infohash, self.peer_id, self.port, 0, self._received, left)

    def _notice_once(self, msg: str) -> None:
        if msg not in self._noticed:
            self._noticed.add(msg)
            self.report.notice(msg)

    async def _wait_notice_loop(self) -> None:
        while True:
            await asyncio.sleep(WAIT_NOTICE_EVERY)
            if self._active == 0:
                trackers = sum(len(t) for t in self.torrent.tiers)
                self.report.notice(waiting_message(trackers, self.dht is not None, self._tried))

    async def _tracker_loop(self) -> None:
        self._add_peers(self._extra_peers)
        if not self.torrent.tiers:
            self._trackers_dead.set()
            return
        event: str | None = "started"
        while True:
            delay = TRACKER_RETRY
            try:
                res = await announce_tiers(self.torrent.tiers, self._params(), event)
            except Exception as e:  # a TrackerError normally; anything else must not end the loop either
                # a TrackerError's text is already scrubbed of URLs; any other type could carry anything
                self._notice_once(f"tracker: {e if isinstance(e, TrackerError) else type(e).__name__}")
                self._trackers_dead.set()
            else:
                self._announced = True
                event = None
                self._add_peers(res.peers)
                delay = announce_delay(res.interval, res.min_interval)
            await self._wait_to_announce(delay)

    async def _dht_loop(self) -> None:
        """Peers from the DHT: always when asked to (magnets), otherwise once the trackers have failed or there are none."""
        if self.dht is None:
            return
        if not self._dht_always:
            await self._trackers_dead.wait()
        await dht_peers_loop(self.dht, self.torrent.infohash, self._add_peers, self.report)

    async def _wait_to_announce(self, delay: float) -> None:
        """Sleep until the next announce is due, but ask sooner (at most once per MIN_ANNOUNCE) when few peers are left."""
        start = time.monotonic()
        while True:
            elapsed = time.monotonic() - start
            if elapsed >= delay or (elapsed >= MIN_ANNOUNCE and len(self._by_addr) < LOW_PEERS):
                return
            await asyncio.sleep(min(delay - elapsed, ANNOUNCE_POLL))

    def _add_peers(self, addrs: list[Addr]) -> None:
        """Take at most MAX_NEW_PEERS new candidates from one announce, picked at random when there are more."""
        unique = list(dict.fromkeys(addrs))
        if len(unique) > MAX_NEW_PEERS * 4:
            unique = random.sample(unique, MAX_NEW_PEERS * 4)
        random.shuffle(unique)
        added = 0
        for addr in unique:
            if added >= MAX_NEW_PEERS:
                break
            added += self._add_peer(addr)

    def _add_peer(self, addr: Addr) -> bool:
        if (
            addr in self._by_addr
            or addr in self._banned
            or len(self._by_addr) >= MAX_PENDING
            or not isinstance(addr[1], int)
            or not (0 < addr[1] < 65536)
        ):
            return False
        task = asyncio.create_task(self._peer_task(addr))
        self._by_addr.setdefault(addr, set()).add(task)  # a later announce retries peers that failed
        self._tasks.add(task)
        task.add_done_callback(lambda t: self._forget(addr, t))
        return True

    def _forget(self, addr: Addr, task: asyncio.Task[None]) -> None:
        self._tasks.discard(task)
        tasks = self._by_addr.get(addr)
        if tasks is not None:
            tasks.discard(task)
            if not tasks:
                del self._by_addr[addr]

    def _ban(self, addr: Addr) -> None:
        """Ban one address (ip and port, so a NATed neighbour on another port is not caught) and cut every
        other connection to it."""
        self._banned.add(addr)
        me = asyncio.current_task()
        for t in list(self._by_addr.get(addr, ())):
            if t is not me:
                t.cancel()

    # --- one peer ---

    async def _peer_task(self, addr: Addr) -> None:
        async with self._slots:
            self._tried += 1
            writer: asyncio.StreamWriter | None = None
            conn: _Conn | None = None
            try:
                async with asyncio.timeout(self._connect_timeout):
                    reader, writer = await asyncio.open_connection(*addr)
                    await peer.handshake(reader, writer, self.torrent.infohash, self.peer_id)
                conn = _Conn(addr, writer)
                self._conns.add(conn)
                self._active += 1
                await self._serve(conn, reader)
            except (PeerError, OSError, TimeoutError, ValueError, UnicodeError):
                pass
            except DownloadError as e:  # e.g. the disk is full: no peer can fix that
                self._fatal = e
                self._done.set()
            finally:
                if conn is not None:
                    for w in conn.works:
                        self.picker.release(w.index)
                    self._conns.discard(conn)
                    self._active -= 1
                    self.picker.peer_left(conn.has)
                if writer is not None:
                    writer.close()

    @staticmethod
    async def _drain(writer: asyncio.StreamWriter) -> None:
        async with asyncio.timeout(REQUEST_TIMEOUT):  # a peer that never reads must not hold us forever
            await writer.drain()

    async def _serve(self, conn: _Conn, reader: asyncio.StreamReader) -> None:
        if self.picker.have:
            conn.send(peer.Msg(peer.BITFIELD, peer.bitfield_bytes(self.picker.have, self.torrent.piece_count)))
        await self._drain(conn.writer)
        reading: asyncio.Task[peer.Msg] | None = None
        try:
            while not self._done.is_set():
                if reading is None:
                    reading = asyncio.create_task(peer.read_message(reader))
                deadline = min(conn.last_progress + IDLE_LIMIT, conn.last_send + KEEPALIVE_EVERY)
                oldest = conn.oldest_request()
                if oldest is not None:
                    deadline = min(deadline, oldest + REQUEST_TIMEOUT)
                # never cancel a read: it would lose bytes
                await asyncio.wait({reading}, timeout=max(0.0, deadline - time.monotonic()))
                if reading.done():
                    msg, reading = reading.result(), None
                    await self._on_message(conn, msg)
                now = time.monotonic()
                oldest = conn.oldest_request()
                if oldest is not None and now - oldest > REQUEST_TIMEOUT:  # keep-alives and HAVEs do not excuse this
                    raise PeerError("peer ignored our requests")
                if now - conn.last_progress > IDLE_LIMIT:
                    raise PeerError("peer went idle")
                for w in [w for w in conn.works if self.picker.has(w.index)]:
                    conn.works.remove(w)  # another peer finished it first (endgame)
                self._fill(conn)
                if now - conn.last_send >= KEEPALIVE_EVERY:
                    conn.send(peer.Msg(None))
                await self._drain(conn.writer)
        finally:
            if reading is not None:
                if reading.done() and not reading.cancelled():
                    reading.exception()  # mark it retrieved
                reading.cancel()

    def _fill(self, conn: _Conn) -> None:
        if conn.choked or not conn.interested:
            return
        now = time.monotonic()
        while conn.outstanding() < PIPELINE:
            work = next((w for w in conn.works if w.next_begin < w.size), None)
            if work is None:
                index = self.picker.pick(conn.has, {w.index for w in conn.works})  # never twice on one peer
                if index is None:
                    return
                size = self.torrent.piece_size(index)
                work = _Work(index, size, [None] * -(-size // peer.BLOCK))
                conn.works.append(work)
            length = min(peer.BLOCK, work.size - work.next_begin)
            work.pending[work.next_begin] = now
            conn.send(peer.request(work.index, work.next_begin, length))
            work.next_begin += length

    async def _on_message(self, conn: _Conn, m: peer.Msg) -> None:
        if m.id is None:
            return  # keep-alive
        n = self.torrent.piece_count
        if m.id == peer.CHOKE:
            conn.choked = True
            for w in conn.works:
                self.picker.release(w.index)
            conn.works.clear()  # a choking peer drops our requests
        elif m.id == peer.UNCHOKE:
            conn.choked = False
            conn.last_progress = time.monotonic()
        elif m.id == peer.BITFIELD:
            if conn.seen_have:
                raise PeerError("bitfield out of order")
            conn.seen_have = True
            conn.has = peer.parse_bitfield(m.payload, n)
            self.picker.peer_joined(conn.has)
            conn.needed = sum(1 for i in conn.has if not self.picker.has(i))
            conn.set_interested(conn.needed > 0)
        elif m.id == peer.HAVE:
            i = peer.parse_have(m.payload)
            if i >= n:
                raise PeerError("have out of range")
            conn.seen_have = True
            if i not in conn.has:
                conn.has.add(i)
                self.picker.peer_got(i)
                if not self.picker.has(i):
                    conn.needed += 1
                    conn.set_interested(True)
        elif m.id == peer.PIECE:
            await self._on_piece(conn, m.payload)
        # REQUEST: not seeding in this phase, so a leecher that asks is ignored. Anything else (extension
        # handshake, port, cancel, unknown ids) is ignored too.

    async def _on_piece(self, conn: _Conn, payload: bytes) -> None:
        index, begin, data = peer.parse_piece(payload)
        work = next((w for w in conn.works if w.index == index), None)
        if work is None or begin not in work.pending:
            return  # unsolicited, a duplicate, or a piece we dropped
        if len(data) != min(peer.BLOCK, work.size - begin):
            raise PeerError("block has the wrong length")
        del work.pending[begin]
        work.blocks[begin // peer.BLOCK] = data
        work.got += len(data)
        self._received += len(data)
        conn.last_progress = time.monotonic()
        if work.got < work.size:
            return
        conn.works.remove(work)
        big = work.size >= HASH_OFFLOAD
        expected = self.torrent.pieces[index]
        ok = await asyncio.to_thread(_sha_ok, work.blocks, expected) if big else _sha_ok(work.blocks, expected)
        if not ok:
            self.picker.release(index)
            self._ban(conn.addr)
            raise PeerError("peer sent a corrupt piece")
        if self.picker.has(index):
            return
        whole = b"".join(b or b"" for b in work.blocks)
        try:
            if big:
                await asyncio.to_thread(self.storage.write_piece, index, whole)
            else:
                self.storage.write_piece(index, whole)
        except OSError as e:
            raise DownloadError(f"cannot write to disk: {e.strerror or e}; partial data kept") from e
        if self.picker.has(index):
            return  # another peer finished it while this one was being written
        self._piece_done(index, work.size)

    def _piece_done(self, index: int, size: int) -> None:
        self.picker.complete(index)
        self.pieces_downloaded += 1
        self._done_bytes += size
        for c in self._conns:
            if index in c.has:
                c.needed -= 1
                if c.needed <= 0:
                    c.set_interested(False)  # nothing left to ask this peer for
        if self.picker.finished:
            self._done.set()
