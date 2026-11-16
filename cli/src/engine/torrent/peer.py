"""Peer wire protocol (BEP 3) and the BEP 10 extension handshake: framing only, no policy."""

import asyncio
import struct
from typing import Any, Protocol

from src.engine.torrent.bencode import BencodeError, decode, encode

PROTOCOL = b"BitTorrent protocol"
CHOKE, UNCHOKE, INTERESTED, NOT_INTERESTED, HAVE, BITFIELD, REQUEST, PIECE, CANCEL = range(9)
EXTENDED = 20
BLOCK = 16384
MAX_MESSAGE = BLOCK + 1024 + (1 << 18)  # a block, or a bitfield for two million pieces


class Writer(Protocol):
    def write(self, data: bytes, /) -> None: ...

    async def drain(self) -> None: ...


class PeerError(Exception):
    pass


class Msg:
    __slots__ = ("id", "payload")

    def __init__(self, id: int | None, payload: bytes = b"") -> None:
        self.id, self.payload = id, payload  # id None is a keep-alive

    def __eq__(self, other: object) -> bool:
        return isinstance(other, Msg) and (self.id, self.payload) == (other.id, other.payload)

    def __repr__(self) -> str:
        return f"Msg({self.id}, {len(self.payload)} bytes)"


def handshake_bytes(infohash: bytes, peer_id: bytes) -> bytes:
    reserved = bytearray(8)
    reserved[5] |= 0x10  # BEP 10 extension protocol
    return bytes([len(PROTOCOL)]) + PROTOCOL + bytes(reserved) + infohash + peer_id


async def read_handshake(reader: asyncio.StreamReader, infohash: bytes, own_id: bytes) -> tuple[bytes, bytes]:
    """Read the remote handshake and check it is for `infohash` and not from ourselves.
    Returns (remote peer id, remote reserved bytes)."""
    try:
        head = await reader.readexactly(1)
        if head[0] != len(PROTOCOL):
            raise PeerError("not a BitTorrent peer")
        rest = await reader.readexactly(len(PROTOCOL) + 8 + 20 + 20)
    except asyncio.IncompleteReadError:
        raise PeerError("connection closed during handshake") from None
    if rest[: len(PROTOCOL)] != PROTOCOL:
        raise PeerError("not a BitTorrent peer")
    reserved = rest[len(PROTOCOL) : len(PROTOCOL) + 8]
    if rest[len(PROTOCOL) + 8 : len(PROTOCOL) + 28] != infohash:
        raise PeerError("peer is serving another torrent")
    remote_id = rest[len(PROTOCOL) + 28 :]
    if remote_id == own_id:
        raise PeerError("connected to ourselves")
    return remote_id, reserved


async def handshake(
    reader: asyncio.StreamReader, writer: Writer, infohash: bytes, peer_id: bytes
) -> tuple[bytes, bytes]:
    """Send ours and read theirs, as the side that dialled. Returns (remote peer id, remote reserved bytes)."""
    writer.write(handshake_bytes(infohash, peer_id))
    await writer.drain()
    return await read_handshake(reader, infohash, peer_id)


def encode_message(m: Msg) -> bytes:
    if m.id is None:
        return b"\x00\x00\x00\x00"
    return struct.pack(">IB", len(m.payload) + 1, m.id) + m.payload


async def read_message(reader: asyncio.StreamReader) -> Msg:
    try:
        (n,) = struct.unpack(">I", await reader.readexactly(4))
        if n == 0:
            return Msg(None)
        if n > MAX_MESSAGE:
            raise PeerError(f"peer sent a {n}-byte message")
        body = await reader.readexactly(n)
    except asyncio.IncompleteReadError:
        raise PeerError("connection closed") from None
    return Msg(body[0], body[1:])


def request(index: int, begin: int, length: int) -> Msg:
    return Msg(REQUEST, struct.pack(">III", index, begin, length))


def cancel(index: int, begin: int, length: int) -> Msg:
    return Msg(CANCEL, struct.pack(">III", index, begin, length))


def parse_request(payload: bytes) -> tuple[int, int, int]:
    if len(payload) != 12:
        raise PeerError("malformed request")
    return struct.unpack(">III", payload)


def piece(index: int, begin: int, data: bytes) -> Msg:
    return Msg(PIECE, struct.pack(">II", index, begin) + data)


def parse_piece(payload: bytes) -> tuple[int, int, bytes]:
    if len(payload) < 8:
        raise PeerError("malformed piece message")
    index, begin = struct.unpack(">II", payload[:8])
    return index, begin, payload[8:]


def have(index: int) -> Msg:
    return Msg(HAVE, struct.pack(">I", index))


def parse_have(payload: bytes) -> int:
    if len(payload) != 4:
        raise PeerError("malformed have")
    return struct.unpack(">I", payload)[0]


def bitfield_bytes(have: set[int], count: int) -> bytes:
    out = bytearray((count + 7) // 8)
    for i in have:
        out[i >> 3] |= 0x80 >> (i & 7)
    return bytes(out)


def parse_bitfield(payload: bytes, count: int) -> set[int]:
    if len(payload) != (count + 7) // 8:
        raise PeerError("bitfield has the wrong length")
    if count % 8 and payload[-1] & (0xFF >> (count % 8)):
        raise PeerError("bitfield has spare bits set")
    return {i for i in range(count) if payload[i >> 3] & (0x80 >> (i & 7))}


def extension_handshake(extensions: dict[bytes, int], metadata_size: int | None = None, reqq: int | None = None) -> Msg:
    """`reqq` is how many requests we accept to have outstanding from one peer."""
    d: dict[bytes, Any] = {b"m": dict(extensions)}
    if metadata_size is not None:
        d[b"metadata_size"] = metadata_size
    if reqq is not None:
        d[b"reqq"] = reqq
    return Msg(EXTENDED, b"\x00" + encode(d))


def parse_extension_handshake(payload: bytes) -> tuple[dict[bytes, int], int | None]:
    if not payload or payload[0] != 0:
        raise PeerError("not an extension handshake")
    try:
        d = decode(payload[1:])
    except BencodeError as e:
        raise PeerError(f"bad extension handshake: {e}") from None
    if not isinstance(d, dict) or not isinstance(d.get(b"m"), dict):
        raise PeerError("bad extension handshake")
    exts = {k: v for k, v in d[b"m"].items() if isinstance(v, int)}
    size = d.get(b"metadata_size")
    return exts, size if isinstance(size, int) else None
