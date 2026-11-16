"""A minimal seeder: accept TCP peers, announce our pieces, unchoke interested peers and serve requests.

It is the counterpart in tests and the upload side of `--seed`; it also serves the info dictionary (BEP 9) when given one.
Peers are hostile input, and none of them may hold the seeder up:
- every request is checked against the torrent before storage is read, and the disk is read off the event loop;
- at most MAX_UNCHOKED peers are served at once; the others wait in the order they became interested, and an unchoked peer
  gives its slot up after `rotate_every` seconds, or `idle_unchoke` seconds without a request, when somebody waits;
- each peer has at most `max_queued` requests waiting (more are dropped, and far more is hung up on), at most
  `max_per_ip` connections come from one address, and a connection that has not handshaken uses no peer slot;
- every write is bounded: a peer that does not read for `write_timeout` seconds, or lets more than MAX_WRITE_BUFFER bytes
  pile up, is aborted, not closed (a close would wait forever to flush), and so is every peer when the seeder stops."""

import asyncio
import contextlib
import itertools
import time
from collections import Counter, deque
from collections.abc import Coroutine
from typing import Any

from src.engine.torrent import metadata, peer
from src.engine.torrent.metainfo import Torrent
from src.engine.torrent.peer import PeerError
from src.engine.torrent.storage import Storage

SERVER_ID = 2  # the extension id peers use for ut_metadata messages to us
MAX_UNCHOKED = 4  # peers we upload to at once; other interested peers wait their turn
MAX_QUEUED = 250  # requests queued per peer (advertised as `reqq`); more are dropped
HANG_UP_FACTOR = 10  # a peer with this many times MAX_QUEUED requests dropped, and no block served meanwhile, is hung up on
IDLE_TIMEOUT = 300.0  # a peer that sends nothing (not even a keep-alive) for this long is dropped
WRITE_TIMEOUT = 30.0
MAX_WRITE_BUFFER = 1 << 20  # bytes waiting to be sent to one peer; a peer that lets more pile up is aborted
STOP_WAIT = 0.4  # seconds `stop` gives the handlers, and then the listening socket, to finish


def _hang_up(writer: asyncio.StreamWriter) -> None:
    """Close the connection; with data still waiting to be sent, abort it, since a close would wait to flush it."""
    if writer.transport.get_write_buffer_size():
        writer.transport.abort()
    else:
        writer.close()


class _Remote:
    """What the seeder knows about one connected peer."""

    def __init__(self, writer: asyncio.StreamWriter, burst: float) -> None:
        self.writer = writer
        self.interested = False
        self.interested_seq = 0  # when it became interested (or was last rotated out): lower waits longer
        self.unchoked = False
        self.unchoked_at = 0.0
        self.last_request = 0.0
        self.dropped = 0  # requests dropped for a full queue since the last block was served
        self.queue: deque[tuple[int, int, int]] = deque()
        self.wake = asyncio.Event()
        self.tokens, self.stamp = burst, time.monotonic()  # the allowance for changes of interest

    def write(self, data: bytes) -> None:
        """Queue bytes for the peer. A peer that lets too much pile up is aborted."""
        transport = self.writer.transport
        if transport.is_closing():
            return
        self.writer.write(data)
        if transport.get_write_buffer_size() > MAX_WRITE_BUFFER:
            transport.abort()

    def send(self, msg: peer.Msg) -> None:
        self.write(peer.encode_message(msg))

    def abort(self) -> None:
        self.writer.transport.abort()


class Seeder:
    def __init__(
        self,
        torrent: Torrent,
        storage: Storage,
        have: set[int],
        peer_id: bytes,
        host: str = "127.0.0.1",
        port: int = 0,
        delay: float = 0.0,
        handshake_timeout: float = 10.0,
        max_peers: int = 16,
        info: bytes | None = None,
        max_unchoked: int = MAX_UNCHOKED,
        max_queued: int = MAX_QUEUED,
        idle_timeout: float = IDLE_TIMEOUT,
        write_timeout: float = WRITE_TIMEOUT,
        max_pending: int = 64,
        max_per_ip: int = 3,
        rotate_every: float = 30.0,
        idle_unchoke: float = 60.0,
        choke_interval: float = 1.0,
        msg_burst: float = 30.0,
        msg_rate: float = 5.0,
    ) -> None:
        self.torrent, self.storage, self.have, self.peer_id = torrent, storage, set(have), peer_id
        self.host, self.port, self.delay = host, port, delay  # delay: seconds before each block, to throttle
        self.handshake_timeout, self.max_peers = handshake_timeout, max_peers
        self.info = info  # the bencoded info dictionary, served to peers that ask for it (BEP 9)
        self.max_unchoked, self.max_queued = max_unchoked, max_queued
        self.idle_timeout, self.write_timeout = idle_timeout, write_timeout
        self.max_pending = max_pending  # connections that have not handshaken yet
        self.max_per_ip = max_per_ip
        self.rotate_every, self.idle_unchoke, self.choke_interval = rotate_every, idle_unchoke, choke_interval
        self.msg_burst, self.msg_rate = msg_burst, msg_rate  # changes of interest allowed at once, and refilled per second
        self.blocks_served = 0
        self.uploaded = 0  # bytes of piece data sent
        self._remotes: list[_Remote] = []  # handshaken peers that are ready for messages
        self.connections = 0
        self.requested_pieces: set[int] = set()
        self._server: asyncio.Server | None = None
        self._writers: set[asyncio.StreamWriter] = set()  # handshaken peers: they hold a slot
        self._pending: set[asyncio.StreamWriter] = set()  # connected, handshake not done
        self._ips: Counter[str] = Counter()
        self._tasks: set[asyncio.Task[Any]] = set()  # every handler and uploader, so stop() can end them
        self._seq = itertools.count(1)
        self._stopping = False

    @property
    def peer_count(self) -> int:
        return len(self._writers)

    def _spawn(self, coro: Coroutine[Any, Any, None]) -> asyncio.Task[None]:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    async def start(self) -> None:
        self._stopping = False
        self._server = await asyncio.start_server(self._handle, self.host, self.port)
        self.port = self._server.sockets[0].getsockname()[1]
        self._spawn(self._choker())

    async def stop(self) -> None:
        """Stop listening and hang up on everybody at once, whatever they are doing. Takes well under a second."""
        self._stopping = True
        if self._server:
            self._server.close()
        for w in [*self._writers, *self._pending]:
            w.transport.abort()
        tasks = set(self._tasks)
        for t in tasks:
            t.cancel()
        if tasks:
            await asyncio.wait(tasks, timeout=STOP_WAIT)
        if self._server:
            with contextlib.suppress(Exception):  # TimeoutError too: a straggler must not hold the exit
                await asyncio.wait_for(self._server.wait_closed(), STOP_WAIT)

    async def _write(self, remote: _Remote, data: bytes) -> None:
        """The one way to send something we wait for: bounded, and a peer that does not read is aborted."""
        remote.write(data)
        try:
            async with asyncio.timeout(self.write_timeout):
                await remote.writer.drain()
        except TimeoutError:
            remote.abort()
            raise

    def _allow_change(self, remote: _Remote) -> bool:
        """A token bucket on changes of interest: each one may cost us a CHOKE or UNCHOKE message."""
        now = time.monotonic()
        remote.tokens = min(self.msg_burst, remote.tokens + (now - remote.stamp) * self.msg_rate)
        remote.stamp = now
        if remote.tokens < 1:
            return False
        remote.tokens -= 1
        return True

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.connections += 1
        peername = writer.get_extra_info("peername")
        host = str(peername[0]) if peername else ""
        if self._stopping or self._ips[host] >= self.max_per_ip or len(self._pending) >= self.max_pending:
            writer.transport.abort()  # no room: hang up before saying anything
            return
        me = asyncio.current_task()
        if me is not None:
            self._tasks.add(me)
        self._ips[host] += 1
        self._pending.add(writer)
        remote = _Remote(writer, self.msg_burst)
        uploader: asyncio.Task[None] | None = None
        try:
            async with asyncio.timeout(self.handshake_timeout):
                _, reserved = await peer.read_handshake(reader, self.torrent.infohash, self.peer_id)
                if len(self._writers) >= self.max_peers:  # no slot: hang up without answering
                    raise PeerError("no free slot")
                self._pending.discard(writer)
                self._writers.add(writer)
                # ours goes out only now: a peer that wants another torrent, or is not BitTorrent, gets no reply
                await self._write(remote, peer.handshake_bytes(self.torrent.infohash, self.peer_id))
                n = self.torrent.piece_count
                theirs = 0  # the id the peer wants us to use for ut_metadata
                if reserved[5] & 0x10:
                    ours = {metadata.UT_METADATA: SERVER_ID} if self.info is not None else {}
                    size = len(self.info) if self.info is not None else None
                    await self._write(remote, peer.encode_message(peer.extension_handshake(ours, size, self.max_queued)))
                await self._write(remote, peer.encode_message(peer.Msg(peer.BITFIELD, peer.bitfield_bytes(self.have, n))))
            self._remotes.append(remote)
            uploader = self._spawn(self._upload(remote))
            while True:
                async with asyncio.timeout(self.idle_timeout):  # a peer that says nothing for long gives up its place
                    m = await peer.read_message(reader)
                if m.id == peer.INTERESTED:
                    if not self._allow_change(remote):
                        raise PeerError("too many changes of interest")
                    if not remote.interested:
                        remote.interested = True
                        remote.interested_seq = next(self._seq)
                    self._rebalance()
                elif m.id == peer.NOT_INTERESTED:
                    if not self._allow_change(remote):
                        raise PeerError("too many changes of interest")
                    remote.interested = False
                    self._choke(remote)
                    self._rebalance()
                elif m.id == peer.EXTENDED and m.payload and self.info is not None:
                    theirs = await self._on_extended(remote, m.payload, theirs)
                elif m.id == peer.REQUEST:
                    self._on_request(remote, peer.parse_request(m.payload))
                elif m.id == peer.CANCEL:
                    with contextlib.suppress(ValueError):
                        remote.queue.remove(peer.parse_request(m.payload))
        except (PeerError, OSError, TimeoutError, ValueError):
            pass
        finally:
            if uploader is not None:
                uploader.cancel()
            if remote in self._remotes:
                self._remotes.remove(remote)
                self._rebalance()
            self._writers.discard(writer)
            self._pending.discard(writer)
            self._ips[host] -= 1
            if not self._ips[host]:
                del self._ips[host]
            if me is not None:
                self._tasks.discard(me)
            _hang_up(writer)

    def _on_request(self, remote: _Remote, req: tuple[int, int, int]) -> None:
        """Check a request against the torrent before anything is read, and queue it if the peer may have it."""
        index, begin, length = req
        if index >= self.torrent.piece_count or index not in self.have:
            raise PeerError("bad request")
        if not 0 < length <= peer.BLOCK * 2:
            raise PeerError("bad request length")
        if begin + length > self.torrent.piece_size(index):
            raise PeerError("request past the end of the piece")
        if not remote.unchoked:
            return  # a choked peer's requests are dropped (BEP 3)
        remote.last_request = time.monotonic()
        if len(remote.queue) >= self.max_queued:
            remote.dropped += 1  # over what we advertised: dropped, as a client that miscounts would not expect a hang-up
            if remote.dropped > self.max_queued * HANG_UP_FACTOR:
                raise PeerError("far too many queued requests")
            return
        remote.queue.append(req)
        remote.wake.set()

    def _choke(self, remote: _Remote) -> None:
        if remote.unchoked:
            remote.unchoked = False
            remote.queue.clear()
            remote.send(peer.Msg(peer.CHOKE))

    def _rebalance(self) -> None:
        """Unchoke interested peers, longest waiting first, while there is a free upload slot."""
        free = self.max_unchoked - sum(1 for r in self._remotes if r.unchoked)
        if free <= 0:
            return
        waiting = sorted((r for r in self._remotes if r.interested and not r.unchoked), key=lambda r: r.interested_seq)
        now = time.monotonic()
        for r in waiting[:free]:
            r.unchoked = True
            r.unchoked_at = r.last_request = now
            r.send(peer.Msg(peer.UNCHOKE))

    def _rotate(self) -> None:
        """When peers wait, take the slot of one that has had it for `rotate_every` seconds, or has asked for nothing for
        `idle_unchoke`; it goes to the back of the line. A peer is choked this way once per period at most."""
        now = time.monotonic()
        waiting = sum(1 for r in self._remotes if r.interested and not r.unchoked)
        for r in sorted((r for r in self._remotes if r.unchoked), key=lambda r: r.unchoked_at):
            if waiting <= 0:
                break
            if now - r.unchoked_at >= self.rotate_every or now - r.last_request >= self.idle_unchoke:
                self._choke(r)
                r.interested_seq = next(self._seq)
                waiting -= 1
        self._rebalance()

    async def _choker(self) -> None:
        while True:
            await asyncio.sleep(self.choke_interval)
            self._rotate()

    async def _upload(self, remote: _Remote) -> None:
        """Answer the queued requests of one peer, one block at a time. A failure hangs up on the peer."""
        try:
            while True:
                while not remote.queue:
                    remote.wake.clear()
                    await remote.wake.wait()
                index, begin, length = remote.queue.popleft()
                if self.delay:
                    await asyncio.sleep(self.delay)
                if not remote.unchoked:
                    continue
                data = await asyncio.to_thread(self.storage.read, index, begin, length)  # the disk may be slow
                if len(data) != length:
                    raise PeerError("data missing on disk")
                if not remote.unchoked:  # choked while the disk was read
                    continue
                self.requested_pieces.add(index)
                self.blocks_served += 1
                self.uploaded += length
                remote.dropped = 0
                await self._write(remote, peer.encode_message(peer.piece(index, begin, data)))
        except (PeerError, OSError, TimeoutError):
            remote.abort()

    async def _on_extended(self, remote: _Remote, payload: bytes, theirs: int) -> int:
        """Handle an extension message; returns the peer's ut_metadata id as far as it is known."""
        assert self.info is not None
        if payload[0] == 0:
            ext_id = peer.parse_extension_handshake(payload)[0].get(metadata.UT_METADATA, 0)
            return ext_id if 0 < ext_id < 256 else 0  # an id is one byte; anything else means no ut_metadata
        if payload[0] != SERVER_ID or not theirs:
            return theirs
        kind, index, _, _ = metadata.parse_message(payload[1:])
        if kind == metadata.REQUEST:
            chunk = self.info[index * metadata.PIECE : (index + 1) * metadata.PIECE]
            msg = metadata.data_piece(theirs, index, len(self.info), chunk) if chunk else metadata.reject_piece(theirs, index)
            await self._write(remote, peer.encode_message(msg))
        return theirs
