"""Scripted peers for session tests: they speak the handshake, then do whatever the test's script says."""

import asyncio
import contextlib
from collections.abc import Awaitable, Callable, Sequence

from src.engine.torrent import metadata, peer
from src.engine.torrent.metainfo import Torrent
from src.engine.torrent.peer import Msg, PeerError
from src.engine.torrent.storage import Storage

Script = Callable[["StubPeer", asyncio.StreamReader, asyncio.StreamWriter], Awaitable[None]]


class StubPeer:
    def __init__(
        self,
        torrent: Torrent,
        script: Script,
        storage: Storage | None = None,
        has: set[int] | None = None,
        handshake: bool = True,
        initial_bitfield: bool = True,
    ) -> None:
        self.torrent, self.script, self.storage = torrent, script, storage
        self.has = set(range(torrent.piece_count)) if has is None else has
        self.reply_handshake = handshake
        self.initial_bitfield = initial_bitfield
        self.port = 0
        self.connections = 0
        self.messages: list[Msg] = []  # everything the session sent, in order
        self.requests: list[tuple[int, int, int]] = []
        self.served: list[int] = []  # metadata pieces sent
        self.first_request_at: float | None = None
        self.closed = asyncio.Event()
        self.closed_at: float | None = None
        self._server: asyncio.Server | None = None

    async def start(self) -> StubPeer:
        self._server = await asyncio.start_server(self._handle, "127.0.0.1", 0)
        self.port = self._server.sockets[0].getsockname()[1]
        return self

    async def stop(self) -> None:
        if self._server:
            self._server.close()

    @property
    def addr(self) -> tuple[str, int]:
        return ("127.0.0.1", self.port)

    async def recv(self, reader: asyncio.StreamReader) -> Msg:
        m = await peer.read_message(reader)
        self.messages.append(m)
        if m.id == peer.REQUEST:
            req = peer.parse_request(m.payload)
            self.requests.append(req)
            if self.first_request_at is None:
                self.first_request_at = asyncio.get_running_loop().time()
        return m

    @staticmethod
    def send(writer: asyncio.StreamWriter, m: Msg) -> None:
        writer.write(peer.encode_message(m))

    def send_bitfield(self, writer: asyncio.StreamWriter) -> None:
        self.send(writer, Msg(peer.BITFIELD, peer.bitfield_bytes(self.has, self.torrent.piece_count)))

    def block(self, req: tuple[int, int, int]) -> Msg:
        assert self.storage is not None
        index, begin, length = req
        return peer.piece(index, begin, self.storage.read(index, begin, length))

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.connections += 1
        try:
            if not self.reply_handshake:
                await reader.read()  # say nothing until the other side gives up
                return
            await peer.handshake(reader, writer, self.torrent.infohash, b"-ST0001-stubstubstub")
            if self.initial_bitfield:
                self.send_bitfield(writer)
            await writer.drain()
            await self.script(self, reader, writer)
        except (PeerError, OSError, asyncio.IncompleteReadError):
            pass
        finally:
            self.closed_at = asyncio.get_running_loop().time()
            self.closed.set()
            with contextlib.suppress(Exception):
                writer.close()


async def serve_everything(stub: StubPeer, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    """The well-behaved script: unchoke whoever is interested and answer every request."""
    while True:
        m = await stub.recv(reader)
        if m.id == peer.INTERESTED:
            stub.send(writer, Msg(peer.UNCHOKE))
        elif m.id == peer.REQUEST:
            stub.send(writer, stub.block(stub.requests[-1]))
        await writer.drain()


async def unchoke_and_ignore(stub: StubPeer, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    """Unchokes, never answers a request, and keeps sending keep-alives."""

    async def chatter() -> None:
        while True:
            stub.send(writer, Msg(None))
            await writer.drain()
            await asyncio.sleep(0.03)

    task = asyncio.create_task(chatter())
    try:
        while True:
            m = await stub.recv(reader)
            if m.id == peer.INTERESTED:
                stub.send(writer, Msg(peer.UNCHOKE))
                await writer.drain()
    finally:
        task.cancel()


def serve_metadata(
    info: bytes,
    claim_size: int | None = None,
    corrupt: set[int] | None = None,
    reject: set[int] | None = None,
    wrong_length: bool = False,
    advertise: bool = True,
    delay: float = 0.0,
    ut_id: int = 7,
    junk_rejects: Sequence[int] = (),
    ask_us: Sequence[int] = (),
    leave_after_first_request: bool = False,
) -> Script:
    """A peer that serves the info dictionary over ut_metadata, with switches for the ways it can misbehave:
    a different `metadata_size` than the truth, flipped bytes in some pieces, rejecting some pieces, a piece of
    the wrong length, or not advertising ut_metadata at all. `junk_rejects` are REJECTs for pieces nobody asked
    for, `ask_us` are REQUESTs for those pieces sent to the fetcher, and `leave_after_first_request` hangs up
    when the first request has arrived."""

    async def script(stub: StubPeer, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        size = len(info) if claim_size is None else claim_size
        exts = {metadata.UT_METADATA: ut_id} if advertise else {}
        stub.send(writer, peer.extension_handshake(exts, size if advertise else None))
        for index in junk_rejects:
            stub.send(writer, metadata.reject_piece(metadata.OUR_ID, index))
        for index in ask_us:
            stub.send(writer, metadata.request_piece(metadata.OUR_ID, index))
        await writer.drain()
        theirs = metadata.OUR_ID
        while True:
            m = await stub.recv(reader)
            if m.id != peer.EXTENDED or not m.payload:
                continue
            if m.payload[0] == 0:
                theirs = peer.parse_extension_handshake(m.payload)[0].get(metadata.UT_METADATA, 0)
                continue
            kind, index, _, _ = metadata.parse_message(m.payload[1:])
            if kind != metadata.REQUEST:
                continue
            if leave_after_first_request:
                return
            if delay:
                await asyncio.sleep(delay)
            chunk = info[index * metadata.PIECE : (index + 1) * metadata.PIECE]
            if reject and index in reject:
                stub.send(writer, metadata.reject_piece(theirs, index))
            else:
                if corrupt and index in corrupt:
                    chunk = bytes(b ^ 0xFF for b in chunk)
                if wrong_length:
                    chunk = chunk[:-1]
                stub.served.append(index)
                stub.send(writer, metadata.data_piece(theirs, index, size, chunk))
            await writer.drain()

    return script
