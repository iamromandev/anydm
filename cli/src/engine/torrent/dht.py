"""A BEP 5 DHT client over UDP: ping, find_node and get_peers, and an iterative lookup for the peers of an infohash.

It only asks. It never answers queries, never announces (the `token` of each node is kept in the result, but
`announce_peer` is not sent) and keeps no routing table between runs.

Every datagram is hostile. It is dropped unless it is small, a well-formed reply whose transaction id we issued
for exactly that address; lists of nodes and peers are capped; addresses that are not public (loopback, private,
link-local, multicast, unspecified, 0.0.0.0/8) are ignored, unless ANYDM_DHT_BOOTSTRAP is set to explicit nodes
and every one of them is itself local, which only happens in tests; the built-in names never allow it, whatever
DNS says; one lookup sends at most MAX_QUERIES queries and runs for at most LOOKUP_TIME; and all outgoing
queries go through one rate limit."""

import asyncio
import contextlib
import heapq
import ipaddress
import os
import secrets
import socket
import struct
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

from src.core.progress import Reporter
from src.engine.torrent.bencode import BencodeError, decode, encode
from src.engine.torrent.tracker import _in_daemon_thread

type Addr = tuple[str, int]

DEFAULT_BOOTSTRAP = (("router.bittorrent.com", 6881), ("dht.transmissionbt.com", 6881), ("router.utorrent.com", 6881))
ENV_BOOTSTRAP = "ANYDM_DHT_BOOTSTRAP"  # "host:port,host:port"; set but empty turns the DHT off
MAX_BOOTSTRAP = 16
ALPHA = 3  # queries in flight at once in one lookup
K = 8  # the lookup is done when the K closest nodes that answered have all been asked
MAX_KRPC = 4096  # bytes in a datagram we are willing to parse
KRPC_DEPTH = 6
MAX_VALUES = 100  # peers taken from one reply
MAX_NODES = 32  # nodes taken from one reply
MAX_SEEN = 512  # distinct nodes remembered in one lookup
MAX_QUERIES = 256  # queries sent in one lookup
MAX_LOOKUP_PEERS = 500
MAX_TOKEN = 128
QUERIES_PER_SECOND = 40.0
QUERY_TIMEOUT = 3.0
LOOKUP_TIME = 45.0
RESOLVE_TIMEOUT = 5.0
ENOUGH_PEERS = 10
RETRY = 60.0  # between lookups while they find few peers
REFRESH = 300.0
_FARTHEST = 1 << 160
_THIS_NETWORK = ipaddress.ip_network("0.0.0.0/8")


class DhtError(Exception):
    pass


@dataclass
class GetPeers:
    node_id: bytes
    token: bytes | None
    peers: list[Addr]
    nodes: list[tuple[bytes, Addr]]


@dataclass
class LookupResult:
    peers: list[Addr] = field(default_factory=list)
    tokens: dict[Addr, bytes] = field(default_factory=dict)  # kept for a future announce_peer; never used
    responded: int = 0  # nodes that gave a valid answer


# --- addresses and compact formats ---


def _routable(host: str, allow_local: bool) -> bool:
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    if ip.is_multicast or ip.is_unspecified or (isinstance(ip, ipaddress.IPv4Address) and ip in _THIS_NETWORK):
        return False
    if allow_local:
        return not (isinstance(ip, ipaddress.IPv4Address) and ip == ipaddress.IPv4Address("255.255.255.255"))
    return ip.is_global


def _addr(blob: bytes, v6: bool) -> Addr | None:
    size = 16 if v6 else 4
    port = struct.unpack(">H", blob[size : size + 2])[0]
    if port == 0:
        return None
    return str(ipaddress.ip_address(blob[:size])), port


def parse_peers(values: object, allow_local: bool) -> list[Addr]:
    """The compact peers of a `values` list: 6 bytes for IPv4, 18 for IPv6; anything else is skipped."""
    out: list[Addr] = []
    if not isinstance(values, list):
        return out
    for v in values:
        if len(out) >= MAX_VALUES:
            break
        if not isinstance(v, bytes) or len(v) not in (6, 18):
            continue
        a = _addr(v, len(v) == 18)
        if a is not None and _routable(a[0], allow_local) and a not in out:
            out.append(a)
    return out


def parse_nodes(blob: object, allow_local: bool, v6: bool = False) -> list[tuple[bytes, Addr]]:
    """Compact node info: 20-byte id plus a 6-byte (IPv4) or 18-byte (IPv6) address; a trailing fragment is ignored."""
    out: list[tuple[bytes, Addr]] = []
    if not isinstance(blob, bytes):
        return out
    size = 38 if v6 else 26
    for i in range(0, len(blob) - size + 1, size):
        if len(out) >= MAX_NODES:
            break
        a = _addr(blob[i + 20 : i + size], v6)
        if a is not None and _routable(a[0], allow_local):
            out.append((blob[i : i + 20], a))
    return out


def bootstrap_from_env(env: Mapping[str, str] | None = None) -> list[Addr]:
    env = os.environ if env is None else env
    raw = env.get(ENV_BOOTSTRAP)
    if raw is None:
        return list(DEFAULT_BOOTSTRAP)
    out: list[Addr] = []
    for part in raw.split(",")[:MAX_BOOTSTRAP]:
        host, _, port = part.strip().rpartition(":")
        if host and port.isdigit() and 0 < int(port) < 65536:
            out.append((host, int(port)))
    return out


def all_local(hosts: Iterable[str]) -> bool:
    """True when there is at least one address and every one is loopback or private."""
    found = False
    for h in hosts:
        try:
            ip = ipaddress.ip_address(h)
        except ValueError:
            return False
        if not (ip.is_loopback or ip.is_private) or (isinstance(ip, ipaddress.IPv4Address) and ip in _THIS_NETWORK):
            return False
        found = True
    return found


# --- the client ---


class _Proto(asyncio.DatagramProtocol):
    def __init__(self) -> None:
        self.pending: dict[bytes, tuple[Addr, asyncio.Future[dict[bytes, Any]]]] = {}

    def datagram_received(self, data: bytes, addr: Any) -> None:
        if len(data) > MAX_KRPC:
            return
        source = (addr[0], addr[1])
        if not any(a == source for a, _ in self.pending.values()):
            return  # nothing was asked of that address: not worth decoding
        try:
            msg = decode(data, max_depth=KRPC_DEPTH)
        except (BencodeError, RecursionError):
            return
        if not isinstance(msg, dict):
            return
        tid = msg.get(b"t")
        entry = self.pending.get(tid) if isinstance(tid, bytes) else None
        if entry is None or entry[0] != source or entry[1].done():
            return  # not an answer to anything we asked that address
        kind, body = msg.get(b"y"), msg.get(b"r")
        if kind == b"e":
            entry[1].set_exception(DhtError("node answered with an error"))
        elif kind == b"r" and isinstance(body, dict) and isinstance(body.get(b"id"), bytes) and len(body[b"id"]) == 20:
            entry[1].set_result(body)

    def error_received(self, exc: Exception) -> None:
        pass  # an ICMP error for a node that is gone: its query just times out


class DhtClient:
    def __init__(
        self,
        bootstrap: Sequence[Addr] | None = None,
        node_id: bytes | None = None,
        rate: float = QUERIES_PER_SECOND,
        timeout: float = QUERY_TIMEOUT,
        allow_local: bool | None = None,
    ) -> None:
        self.bootstrap = list(DEFAULT_BOOTSTRAP if bootstrap is None else bootstrap)
        self.node_id = node_id or secrets.token_bytes(20)
        self.rate, self.timeout = rate, timeout
        # unless told, local peers are believed only for bootstrap nodes someone chose: never for the built-in names,
        # which come from DNS and so from whoever controls the resolver
        default_names = {h.lower() for h, _ in DEFAULT_BOOTSTRAP}
        builtin = bootstrap is None or any(h.lower() in default_names for h, _ in bootstrap)
        self.allow_local = False if builtin else allow_local
        self.queries_sent = 0
        self._seeds: list[Addr] | None = None
        self._transport: asyncio.DatagramTransport | None = None
        self._proto = _Proto()
        self._next_send = 0.0

    async def __aenter__(self) -> DhtClient:
        await self._open()
        return self

    async def __aexit__(self, *exc: object) -> None:
        self.close()

    def close(self) -> None:
        if self._transport is not None:
            self._transport.close()
            self._transport = None
        for _, fut in list(self._proto.pending.values()):
            fut.cancel()

    async def _open(self) -> None:
        if self._transport is None:
            loop = asyncio.get_running_loop()
            self._transport, _ = await loop.create_datagram_endpoint(
                lambda: self._proto, local_addr=("0.0.0.0", 0), family=socket.AF_INET
            )

    async def _resolve(self) -> list[Addr]:
        """The bootstrap nodes as IPv4 addresses; a name that does not resolve is skipped. Only a resolution that
        found something is kept: a transient DNS failure is tried again by the next lookup."""
        if self._seeds is not None:
            return self._seeds

        async def one(host: str, port: int) -> list[Addr]:
            try:
                ipaddress.ip_address(host)
                return [(host, port)] if ":" not in host else []
            except ValueError:
                pass
            try:
                async with asyncio.timeout(RESOLVE_TIMEOUT):
                    infos = await _in_daemon_thread(socket.getaddrinfo, host, port, socket.AF_INET, socket.SOCK_DGRAM)
            except (OSError, TimeoutError):
                return []
            return [(str(i[4][0]), port) for i in infos[:2]]

        found = await asyncio.gather(*(one(h, p) for h, p in self.bootstrap[:MAX_BOOTSTRAP]))
        seeds = list(dict.fromkeys(a for group in found for a in group))
        if seeds:
            if self.allow_local is None:
                self.allow_local = all_local(h for h, _ in seeds)
            self._seeds = seeds
        return seeds

    async def _throttle(self) -> None:
        loop = asyncio.get_running_loop()
        now = loop.time()
        at = max(now, self._next_send)
        self._next_send = at + 1.0 / self.rate
        if at > now:
            await asyncio.sleep(at - now)

    async def _query(self, addr: Addr, q: bytes, args: dict[bytes, Any]) -> dict[bytes, Any]:
        await self._open()
        await self._resolve()
        pending = self._proto.pending
        tid = secrets.token_bytes(4)
        while tid in pending:
            tid = secrets.token_bytes(4)
        fut: asyncio.Future[dict[bytes, Any]] = asyncio.get_running_loop().create_future()
        pending[tid] = ((addr[0], addr[1]), fut)
        try:
            await self._throttle()
            assert self._transport is not None
            data = encode({b"t": tid, b"y": b"q", b"q": q, b"a": {b"id": self.node_id, **args}})
            try:
                self._transport.sendto(data, addr)
            except OSError:
                raise DhtError("cannot send") from None
            self.queries_sent += 1
            try:
                async with asyncio.timeout(self.timeout):
                    return await fut
            except TimeoutError:
                raise DhtError("no answer") from None
        finally:
            pending.pop(tid, None)

    async def ping(self, addr: Addr) -> bytes:
        """The node id of the node at `addr`."""
        return (await self._query(addr, b"ping", {}))[b"id"]

    async def find_node(self, addr: Addr, target: bytes) -> list[tuple[bytes, Addr]]:
        r = await self._query(addr, b"find_node", {b"target": target})
        return parse_nodes(r.get(b"nodes"), bool(self.allow_local))

    async def get_peers(self, addr: Addr, infohash: bytes) -> GetPeers:
        r = await self._query(addr, b"get_peers", {b"info_hash": infohash})
        local = bool(self.allow_local)
        token = r.get(b"token")
        return GetPeers(
            r[b"id"],
            token if isinstance(token, bytes) and len(token) <= MAX_TOKEN else None,
            parse_peers(r.get(b"values"), local),
            parse_nodes(r.get(b"nodes"), local),  # `nodes6` is not followed: the socket is IPv4
        )

    async def lookup(
        self, infohash: bytes, on_peers: Callable[[list[Addr]], None] | None = None, max_time: float = LOOKUP_TIME
    ) -> LookupResult:
        """Walk towards `infohash`: ask the nearest known node not yet asked, ALPHA at a time, until the K
        nearest that answered have all been asked, or the limits are reached. New peers go to `on_peers` as
        they are found."""
        res = LookupResult()
        seeds = await self._resolve()
        want = int.from_bytes(infohash, "big")
        heap: list[tuple[int, int, Addr]] = []
        seen: set[Addr] = set()
        seq = 0
        for a in seeds:
            heapq.heappush(heap, (_FARTHEST, seq, a))
            seen.add(a)
            seq += 1
        closest: list[int] = []  # distances of the nodes that answered, nearest first, at most K
        inflight: dict[asyncio.Task[GetPeers], Addr] = {}
        found: dict[Addr, None] = {}
        sent = 0
        try:
            async with asyncio.timeout(max_time):
                while True:
                    while len(inflight) < ALPHA and heap and sent < MAX_QUERIES:
                        dist, _, a = heapq.heappop(heap)
                        if len(closest) >= K and dist > closest[-1]:
                            heap.clear()  # the K nearest answered; the rest are further away
                            break
                        inflight[asyncio.create_task(self.get_peers(a, infohash))] = a
                        sent += 1
                    if not inflight:
                        break
                    done, _ = await asyncio.wait(inflight, return_when=asyncio.FIRST_COMPLETED)
                    for t in done:
                        a = inflight.pop(t)
                        if t.cancelled() or t.exception() is not None:
                            continue
                        r = t.result()
                        res.responded += 1
                        dist = int.from_bytes(r.node_id, "big") ^ want
                        closest = sorted([*closest, dist])[:K]
                        if r.token is not None:
                            res.tokens[a] = r.token
                        new = [p for p in r.peers if p not in found][: max(0, MAX_LOOKUP_PEERS - len(found))]
                        if new:
                            found.update(dict.fromkeys(new))
                            if on_peers is not None:
                                with contextlib.suppress(Exception):  # our caller's trouble must not end the walk
                                    on_peers(new)
                        for nid, naddr in r.nodes:
                            if naddr not in seen and len(seen) < MAX_SEEN and nid != self.node_id:
                                seen.add(naddr)
                                seq += 1
                                heapq.heappush(heap, (int.from_bytes(nid, "big") ^ want, seq, naddr))
        except TimeoutError:
            pass
        finally:
            for t in inflight:
                t.cancel()
            await asyncio.gather(*inflight, return_exceptions=True)
        res.peers = list(found)
        return res


async def dht_peers_loop(
    dht: DhtClient,
    infohash: bytes,
    add_peers: Callable[[list[Addr]], None],
    report: Reporter | None = None,
    retry: float = RETRY,
    refresh: float = REFRESH,
) -> None:
    """Look up the peers again and again, handing each new one to `add_peers`, until cancelled. A lookup that
    finds few peers is repeated after `retry`, one that finds enough after `refresh`."""
    told = False
    while True:
        try:
            res = await dht.lookup(infohash, add_peers)
        except Exception:  # one failed lookup must not end the loop
            res = LookupResult()
        if res.responded == 0 and not told and report is not None:
            told = True
            report.notice("dht: no node answered; will try again")
        await asyncio.sleep(refresh if len(res.peers) >= ENOUGH_PEERS else retry)
