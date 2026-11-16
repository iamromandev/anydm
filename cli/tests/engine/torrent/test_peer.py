import asyncio
import struct

import pytest
from src.engine.torrent import peer
from src.engine.torrent.bencode import decode
from src.engine.torrent.peer import Msg, PeerError

IH = bytes(range(20))


def feed(data: bytes) -> asyncio.StreamReader:
    r = asyncio.StreamReader()
    r.feed_data(data)
    r.feed_eof()
    return r


class Sink:
    def __init__(self) -> None:
        self.data = b""

    def write(self, d: bytes) -> None:
        self.data += d

    async def drain(self) -> None:
        pass


def test_handshake_bytes_layout() -> None:
    hs = peer.handshake_bytes(IH, b"P" * 20)
    assert len(hs) == 68
    assert hs[0] == 19 and hs[1:20] == b"BitTorrent protocol"
    assert hs[28:48] == IH and hs[48:] == b"P" * 20


async def test_handshake_returns_remote_peer_id() -> None:
    sink = Sink()
    reply = peer.handshake_bytes(IH, b"R" * 20)
    pid, reserved = await peer.handshake(feed(reply), sink, IH, b"L" * 20)
    assert pid == b"R" * 20
    assert sink.data == peer.handshake_bytes(IH, b"L" * 20)
    assert reserved[5] & 0x10  # we advertise the extension protocol, and so does the helper


async def test_handshake_rejects_wrong_infohash() -> None:
    with pytest.raises(PeerError):
        await peer.handshake(feed(peer.handshake_bytes(b"x" * 20, b"R" * 20)), Sink(), IH, b"L" * 20)


async def test_handshake_rejects_other_protocol() -> None:
    bad = bytes([5]) + b"hello" + bytes(63)
    with pytest.raises(PeerError):
        await peer.handshake(feed(bad), Sink(), IH, b"L" * 20)


async def test_handshake_rejects_a_truncated_reply() -> None:
    with pytest.raises(PeerError):
        await peer.handshake(feed(b"\x13Bit"), Sink(), IH, b"L" * 20)


@pytest.mark.parametrize(
    ("msg", "wire"),
    [
        (Msg(None, b""), b"\x00\x00\x00\x00"),
        (Msg(peer.UNCHOKE, b""), b"\x00\x00\x00\x01\x01"),
        (Msg(peer.HAVE, struct.pack(">I", 7)), b"\x00\x00\x00\x05\x04\x00\x00\x00\x07"),
    ],
)
async def test_messages_round_trip(msg: Msg, wire: bytes) -> None:
    assert peer.encode_message(msg) == wire
    assert await peer.read_message(feed(wire)) == msg


async def test_oversized_message_is_rejected() -> None:
    with pytest.raises(PeerError):
        await peer.read_message(feed(struct.pack(">I", 1 << 30) + b"x"))


async def test_truncated_message_is_a_peer_error() -> None:
    with pytest.raises(PeerError):
        await peer.read_message(feed(b"\x00\x00\x00\x09\x07abc"))


def test_request_and_piece_payloads() -> None:
    m = peer.request(3, 16384, 16384)
    assert m.id == peer.REQUEST and peer.parse_request(m.payload) == (3, 16384, 16384)
    p = peer.piece(3, 0, b"data")
    assert peer.parse_piece(p.payload) == (3, 0, b"data")
    with pytest.raises(PeerError):
        peer.parse_request(b"short")
    with pytest.raises(PeerError):
        peer.parse_piece(b"short")


def test_bitfield_round_trip_and_validation() -> None:
    have = {0, 3, 9}
    wire = peer.bitfield_bytes(have, 10)
    assert wire == bytes([0b10010000, 0b01000000])
    assert peer.parse_bitfield(wire, 10) == have
    with pytest.raises(PeerError):
        peer.parse_bitfield(b"\x00", 10)  # wrong length
    with pytest.raises(PeerError):
        peer.parse_bitfield(bytes([0, 0b00100000]), 10)  # spare bit set


def test_extension_handshake_payload() -> None:
    m = peer.extension_handshake({b"ut_metadata": 2}, metadata_size=123)
    assert m.id == peer.EXTENDED and m.payload[0] == 0
    ext, size = peer.parse_extension_handshake(m.payload)
    assert ext == {b"ut_metadata": 2} and size == 123
    with pytest.raises(PeerError):
        peer.parse_extension_handshake(b"\x00garbage")


async def test_handshake_rejects_a_peer_id_equal_to_ours() -> None:
    with pytest.raises(PeerError, match="ourselves"):
        await peer.handshake(feed(peer.handshake_bytes(IH, b"L" * 20)), Sink(), IH, b"L" * 20)


async def test_read_handshake_writes_nothing_and_returns_the_remote_id_and_reserved_bytes() -> None:
    pid, reserved = await peer.read_handshake(feed(peer.handshake_bytes(IH, b"R" * 20)), IH, b"L" * 20)
    assert pid == b"R" * 20 and reserved[5] & 0x10


@pytest.mark.parametrize("data", [peer.handshake_bytes(b"x" * 20, b"R" * 20), b"GET / HTTP/1.1\r\n\r\n" + bytes(60), b"\x13Bit"])
async def test_read_handshake_rejects_another_torrent_a_stranger_and_a_truncated_one(data: bytes) -> None:
    with pytest.raises(PeerError):
        await peer.read_handshake(feed(data), IH, b"L" * 20)


def test_extension_handshake_carries_reqq_when_given() -> None:
    d = decode(peer.extension_handshake({}, reqq=250).payload[1:])
    assert isinstance(d, dict) and d[b"reqq"] == 250
    d = decode(peer.extension_handshake({}).payload[1:])
    assert isinstance(d, dict) and b"reqq" not in d
