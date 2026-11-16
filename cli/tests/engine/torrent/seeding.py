"""Test helper: a torrent on disk plus a Seeder serving it."""

import asyncio
import socket
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from src.engine.torrent import peer
from src.engine.torrent.metainfo import Torrent, parse
from src.engine.torrent.seed import Seeder
from src.engine.torrent.storage import Storage

from tests.engine.torrent.helpers import info_bytes, make_torrent


@dataclass
class Swarm:
    torrent: Torrent
    raw: bytes
    payload: bytes
    seeder: Seeder
    storage: Storage


async def make_seeder(
    root: Path,
    files: dict[str, bytes],
    piece_len: int = 16384,
    corrupt: set[int] | None = None,
    delay: float = 0.0,
    options: dict[str, Any] | None = None,
    **kw,
) -> Swarm:
    """`options` are keyword arguments for the Seeder; the rest goes to `make_torrent`."""
    raw, payload = make_torrent(files, piece_len=piece_len, **kw)
    torrent = parse(raw)
    storage = Storage(torrent, root)
    data = bytearray(payload)
    for i in corrupt or ():  # serve garbage for these pieces while still claiming to have them
        start = i * piece_len
        data[start : start + torrent.piece_size(i)] = b"\xff" * torrent.piece_size(i)
    for i in range(torrent.piece_count):
        storage.write_piece(i, bytes(data[i * piece_len : i * piece_len + torrent.piece_size(i)]))
    seeder = Seeder(torrent, storage, set(range(torrent.piece_count)), b"-SD0001-seedseedseed", delay=delay, info=info_bytes(raw), **(options or {}))
    await seeder.start()
    return Swarm(torrent, raw, payload, seeder, storage)


async def wait_until(cond: Callable[[], object], timeout: float = 5.0) -> None:
    """Poll until `cond()` is true; fails after `timeout`. Use this instead of a fixed sleep."""
    end = asyncio.get_running_loop().time() + timeout
    while not cond():
        assert asyncio.get_running_loop().time() < end, "condition not met in time"
        await asyncio.sleep(0.01)


async def until_hung_up(reader: asyncio.StreamReader) -> bytes:
    """What the other side sent before it closed the connection (an orderly close or a reset); fails if it stays open."""
    try:
        return await asyncio.wait_for(reader.read(), 2)
    except ConnectionResetError:
        return b""


async def connect(swarm: Swarm, tag: bytes = b"a") -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
    """A handshaken raw peer: (reader, writer) with the seeder's bitfield already read."""
    reader, writer = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
    await peer.handshake(reader, writer, swarm.torrent.infohash, b"-AD0001-" + tag * 12)
    while (await asyncio.wait_for(peer.read_message(reader), 2)).id != peer.BITFIELD:
        pass  # the extension handshake comes first
    return reader, writer


def send(writer: asyncio.StreamWriter, msg: peer.Msg) -> None:
    writer.write(peer.encode_message(msg))


async def next_msg(reader: asyncio.StreamReader, timeout: float = 2) -> peer.Msg:
    return await asyncio.wait_for(peer.read_message(reader), timeout)


async def stalled_peer(
    swarm: Swarm, tag: bytes = b"s", requests: int = 200, port: int | None = None
) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
    """A peer that is unchoked, asks for `requests` blocks and then never reads again, with a tiny receive buffer,
    so the seeder's writes to it back up. It dials the swarm's seeder, or `port` (another process serving the same torrent)."""
    sock = socket.socket()
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    sock.setblocking(False)
    await asyncio.get_running_loop().sock_connect(sock, ("127.0.0.1", port or swarm.seeder.port))
    reader, writer = await asyncio.open_connection(sock=sock)
    await peer.handshake(reader, writer, swarm.torrent.infohash, b"-AD0001-" + tag * 12)
    send(writer, peer.Msg(peer.INTERESTED))
    while (await next_msg(reader)).id != peer.UNCHOKE:
        pass
    for _ in range(requests):
        send(writer, peer.request(0, 0, peer.BLOCK))
    await writer.drain()
    if requests and port:
        await asyncio.sleep(0.3)  # a seeder we cannot look into: give its writes time to back up
    elif requests:  # wait until the seeder's own writes have backed up
        await wait_until(lambda: any(r.writer.transport.get_write_buffer_size() > 0 for r in swarm.seeder._remotes))
    return reader, writer
