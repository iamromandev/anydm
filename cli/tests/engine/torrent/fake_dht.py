"""A fake DHT network on 127.0.0.1 for tests: nodes that answer KRPC queries, honestly or not."""

import asyncio
import itertools
import os
import socket
import struct
from dataclasses import dataclass, field

from src.engine.torrent.bencode import decode, encode

Addr = tuple[str, int]


def compact_peer(addr: Addr) -> bytes:
    fam = socket.AF_INET6 if ":" in addr[0] else socket.AF_INET
    return socket.inet_pton(fam, addr[0]) + struct.pack(">H", addr[1])


def compact_node(node_id: bytes, addr: Addr) -> bytes:
    return node_id + compact_peer(addr)


def near(target: bytes, distance: int) -> bytes:
    """A node id whose XOR distance to `target` is about 2**distance."""
    n = int.from_bytes(target, "big") ^ (1 << distance)
    return n.to_bytes(20, "big")


@dataclass
class Gauge:
    """Counts the queries being answered at this moment across several nodes."""

    current: int = 0
    peak: int = 0


@dataclass
class FakeNode(asyncio.DatagramProtocol):
    node_id: bytes
    mode: str = "ok"  # ok | junk | wrong_tid | flood | spoof | huge | deep | silent | no_id | error
    peers: list[Addr] = field(default_factory=list)  # handed out in `values`
    raw_values: list[bytes] | None = None  # sent as `values` exactly
    neighbours: list[tuple[bytes, Addr]] = field(default_factory=list)  # handed out in `nodes`
    delay: float = 0.0
    gauge: Gauge | None = None
    token: bytes = b"tok-" + b"x" * 4
    queries: list[tuple[bytes, dict]] = field(default_factory=list)
    tids: list[bytes] = field(default_factory=list)  # the transaction id of every query
    port: int = 0
    _transport: asyncio.DatagramTransport | None = None
    _spoofer: socket.socket | None = None

    async def start(self) -> FakeNode:
        loop = asyncio.get_running_loop()
        self._transport, _ = await loop.create_datagram_endpoint(lambda: self, local_addr=("127.0.0.1", 0))
        self.port = self._transport.get_extra_info("sockname")[1]
        return self

    def stop(self) -> None:
        if self._transport:
            self._transport.close()
        if self._spoofer:
            self._spoofer.close()

    @property
    def addr(self) -> Addr:
        return ("127.0.0.1", self.port)

    def datagram_received(self, data: bytes, addr: Addr) -> None:
        try:
            msg = decode(data)
        except Exception:
            return
        if not isinstance(msg, dict) or msg.get(b"y") != b"q":
            return
        args = msg.get(b"a", {})
        self.queries.append((msg[b"q"], args))
        self.tids.append(msg[b"t"])
        asyncio.get_running_loop().create_task(self._answer(msg, args, addr))

    async def _answer(self, msg: dict, args: dict, addr: Addr) -> None:
        if self.gauge:
            self.gauge.current += 1
            self.gauge.peak = max(self.gauge.peak, self.gauge.current)
        try:
            if self.delay:
                await asyncio.sleep(self.delay)
            self._send(msg, args, addr)
        finally:
            if self.gauge:
                self.gauge.current -= 1

    def _reply_body(self, query: bytes) -> dict:
        r: dict = {b"id": self.node_id}
        if query == b"get_peers":
            r[b"token"] = self.token
            if self.peers or self.raw_values is not None:
                r[b"values"] = self.raw_values if self.raw_values is not None else [compact_peer(p) for p in self.peers]
        if query in (b"get_peers", b"find_node"):
            r[b"nodes"] = b"".join(compact_node(i, a) for i, a in self.neighbours)
        return r

    def _send(self, msg: dict, args: dict, addr: Addr) -> None:
        assert self._transport is not None
        tid = msg[b"t"]
        mode = self.mode
        if mode == "silent":
            return
        if mode == "junk":
            self._transport.sendto(os.urandom(60), addr)
        elif mode == "wrong_tid":
            self._transport.sendto(encode({b"t": bytes(b ^ 1 for b in tid), b"y": b"r", b"r": self._reply_body(msg[b"q"])}), addr)
        elif mode == "spoof":  # an answer that comes from another port than the one asked
            if self._spoofer is None:
                self._spoofer = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                self._spoofer.bind(("127.0.0.1", 0))
            self._spoofer.sendto(encode({b"t": tid, b"y": b"r", b"r": self._reply_body(msg[b"q"])}), addr)
        elif mode == "flood":
            body = self._reply_body(msg[b"q"])
            body[b"nodes"] = b"".join(compact_node(os.urandom(20), ("127.0.0.1", 20000 + i)) for i in range(3000))
            self._transport.sendto(encode({b"t": tid, b"y": b"r", b"r": body}), addr)
        elif mode == "huge":
            body = self._reply_body(msg[b"q"])
            body[b"junk"] = b"z" * 100_000
            self._transport.sendto(encode({b"t": tid, b"y": b"r", b"r": body}), addr)
        elif mode == "deep":
            self._transport.sendto(b"d1:t2:" + tid + b"1:y1:r1:r" + b"l" * 5000 + b"e" * 5000 + b"e", addr)
        elif mode == "no_id":
            body = self._reply_body(msg[b"q"])
            del body[b"id"]
            self._transport.sendto(encode({b"t": tid, b"y": b"r", b"r": body}), addr)
        elif mode == "error":
            self._transport.sendto(encode({b"t": tid, b"y": b"e", b"e": [201, b"nope"]}), addr)
        else:
            self._transport.sendto(encode({b"t": tid, b"y": b"r", b"r": self._reply_body(msg[b"q"])}), addr)


async def chain(infohash: bytes, holder_peers: list[Addr], hops: int = 3, decoys: int = 0) -> list[FakeNode]:
    """boot -> n1 -> ... -> holder, each node knowing only the next one, so a lookup must walk the chain; the
    ids get closer to the infohash along the way. The last node holds the peers. The first is the bootstrap node."""
    nodes = [await FakeNode(near(infohash, 150 - 20 * i)).start() for i in range(hops + 1)]
    nodes[-1].peers = list(holder_peers)
    for a, b in itertools.pairwise(nodes):
        a.neighbours = [(b.node_id, b.addr)]
    for _ in range(decoys):
        d = await FakeNode(os.urandom(20)).start()
        nodes[0].neighbours.append((d.node_id, d.addr))
        nodes.append(d)
    return nodes
