"""Tracker clients: HTTP (BEP 3, compact BEP 23) and UDP (BEP 15), and announce-list tiers (BEP 12).

Everything a tracker or a .torrent file says is hostile, and so is the tracker URL itself (a magnet link can carry any
number of them): a host that resolves to a loopback, private, link-local or otherwise non-public address is only
contacted when the URL path ends in /announce, or when ANYDM_ALLOW_LOCAL_TRACKERS=1; otherwise it is skipped.
The name is resolved once per request and the connection goes to an address that was checked. Any failure, whatever its type, leaves `announce` as a
`TrackerError`. Messages carry only scheme://host, never the path or query, which often hold a private passkey,
and never raw control characters."""

import asyncio
import contextlib
import http.client
import ipaddress
import os
import random
import socket
import ssl
import struct
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field

from src.engine.torrent.bencode import BencodeError, decode

MAX_RESPONSE = 1 << 20
MAX_PEERS_PER_RESPONSE = 2000
MAX_REDIRECTS = 3
UDP_MAGIC = 0x41727101980
TIER_CONCURRENCY = 16
DEFAULT_INTERVAL = 1800
ENV_ALLOW_LOCAL = "ANYDM_ALLOW_LOCAL_TRACKERS"  # "1": trackers on loopback and LAN addresses, whatever the URL path


class TrackerError(Exception):
    pass


@dataclass(frozen=True)
class AnnounceParams:
    infohash: bytes
    peer_id: bytes
    port: int
    uploaded: int = 0
    downloaded: int = 0
    left: int = 0


@dataclass
class AnnounceResult:
    interval: int
    peers: list[tuple[str, int]] = field(default_factory=list)
    min_interval: int = 0


def _clean(text: str, limit: int) -> str:
    """Printable characters only, so a tracker cannot drive the terminal, and bounded in length."""
    return "".join(c for c in text if c.isprintable())[:limit]


def _where(url: object) -> str:
    """scheme://host of a tracker URL for messages. Never raises, never shows the path or query."""
    try:
        if not isinstance(url, str):
            return "?"
        p = urllib.parse.urlsplit(url)
        return f"{_clean(p.scheme, 10) or '?'}://{_clean(p.hostname or '?', 100) or '?'}"
    except Exception:
        return "?"


def _reason(e: BaseException) -> str:
    """A short description of a failure that cannot contain the URL."""
    if isinstance(e, urllib.error.HTTPError):
        return f"HTTP {e.code}"
    if isinstance(e, urllib.error.URLError):
        return _reason(e.reason) if isinstance(e.reason, BaseException) else "cannot connect"
    if isinstance(e, TimeoutError | socket.timeout):
        return "timed out"
    if isinstance(e, OSError) and e.strerror:
        return _clean(e.strerror, 100)
    return type(e).__name__


def _is_local(ip: str) -> bool:
    addr = ipaddress.ip_address(ip.split("%")[0])
    if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped is not None:
        addr = addr.ipv4_mapped
    return not addr.is_global


def _vet_host(url: str, host: str, port: int, path: str) -> list[str]:
    """Resolve `host` once (call this in a thread) and say where we may connect: every resolved address is checked,
    and one that is not public makes the tracker local, which is allowed for an /announce URL or when opted in."""
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except UnicodeError:
        raise TrackerError(f"{_where(url)}: bad host name") from None
    ips = list(dict.fromkeys(str(i[4][0]) for i in infos))
    if not ips:
        raise TrackerError(f"{_where(url)}: cannot resolve")
    if any(_is_local(ip) for ip in ips) and not (path.endswith("/announce") or os.environ.get(ENV_ALLOW_LOCAL) == "1"):
        raise TrackerError(f"{_where(url)}: skipped, a local address that is not an /announce URL ({ENV_ALLOW_LOCAL}=1 allows it)")
    return ips


def _pinned(ips: list[str]) -> Callable[..., socket.socket]:
    """A replacement for socket.create_connection that ignores the name and tries the addresses already checked."""

    def create(address: tuple[str, int], timeout: float | None = None, source_address: tuple[str, int] | None = None) -> socket.socket:
        last: OSError | None = None
        for ip in ips:
            try:
                return socket.create_connection((ip, address[1]), timeout, source_address)
            except OSError as e:
                last = e
        raise last or OSError("no address")

    return create


def _vetted(cls: type[http.client.HTTPConnection], req: urllib.request.Request) -> Callable[..., http.client.HTTPConnection]:
    """A connection factory for `req` that has resolved and checked its host (a redirect target is a new request, so it
    is checked again). Behind a proxy the proxy does the resolving; the host is checked but not pinned."""
    u = urllib.parse.urlsplit(req.full_url)
    default = 443 if u.scheme == "https" else 80
    ips = _vet_host(req.full_url, u.hostname or "", u.port or default, u.path)
    proxied = bool(getattr(req, "_tunnel_host", None)) or req.selector.startswith(("http://", "https://"))

    def make(host: str, **kw: object) -> http.client.HTTPConnection:
        conn = cls(host, **kw)  # ty: ignore[invalid-argument-type]
        if not proxied:
            conn._create_connection = _pinned(ips)  # ty: ignore[unresolved-attribute]
        return conn

    return make


class _VettedHTTP(urllib.request.HTTPHandler):
    def http_open(self, req):
        return self.do_open(_vetted(http.client.HTTPConnection, req), req)


class _VettedHTTPS(urllib.request.HTTPSHandler):
    def __init__(self) -> None:
        self._ctx = ssl.create_default_context()
        super().__init__(context=self._ctx)

    def https_open(self, req):
        return self.do_open(_vetted(http.client.HTTPSConnection, req), req, context=self._ctx)


def _port_ok(port: object) -> bool:
    return isinstance(port, int) and not isinstance(port, bool) and 0 < port < 65536


def _compact(blob: bytes, v6: bool = False) -> list[tuple[str, int]]:
    size = 18 if v6 else 6
    out = []
    for i in range(0, len(blob) - size + 1, size):
        port = struct.unpack(">H", blob[i + size - 2 : i + size])[0]
        if port:
            out.append((str(ipaddress.ip_address(blob[i : i + size - 2])), port))
        if len(out) >= MAX_PEERS_PER_RESPONSE:
            break
    return out


def _dict_peer(p: object) -> tuple[str, int] | None:
    """An `ip` that really is an address (never a host name, so no DNS lookup is ever triggered) and a valid port."""
    if not isinstance(p, dict) or not isinstance(p.get(b"ip"), bytes) or not _port_ok(p.get(b"port")):
        return None
    try:
        text = p[b"ip"].decode("ascii")
        if "%" in text:
            return None
        return str(ipaddress.ip_address(text)), p[b"port"]
    except ValueError:  # includes UnicodeDecodeError
        return None


def _parse_http(body: bytes) -> AnnounceResult:
    try:
        d = decode(body)
    except BencodeError as e:
        raise TrackerError(f"bad tracker response: {e}") from e
    if not isinstance(d, dict):
        raise TrackerError("bad tracker response")
    if b"failure reason" in d:
        reason = d[b"failure reason"]
        raise TrackerError(_clean(reason.decode(errors="replace"), 200) if isinstance(reason, bytes) else "tracker failure")
    interval, minimum = d.get(b"interval"), d.get(b"min interval")
    res = AnnounceResult(
        interval if isinstance(interval, int) and interval > 0 else DEFAULT_INTERVAL,
        min_interval=minimum if isinstance(minimum, int) and minimum > 0 else 0,
    )
    peers = d.get(b"peers")
    if isinstance(peers, bytes):
        res.peers += _compact(peers)
    elif isinstance(peers, list):
        for p in peers[: MAX_PEERS_PER_RESPONSE * 2]:
            if (peer := _dict_peer(p)) is not None:
                res.peers.append(peer)
    if isinstance(d.get(b"peers6"), bytes):
        res.peers += _compact(d[b"peers6"], v6=True)
    del res.peers[MAX_PEERS_PER_RESPONSE:]
    return res


class _Redirects(urllib.request.HTTPRedirectHandler):
    """At most MAX_REDIRECTS redirects, and only to http(s)."""

    max_redirections = MAX_REDIRECTS
    max_repeats = MAX_REDIRECTS

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme not in ("http", "https"):
            raise urllib.error.HTTPError(req.full_url, code, "redirect to a non-http URL", headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _http_get(url: str, params: AnnounceParams, event: str | None, timeout: float) -> AnnounceResult:
    p = urllib.parse.urlsplit(url)
    if not p.hostname or (p.port or 1) <= 0:  # `.port` raises ValueError for a bad port
        raise TrackerError(f"{_where(url)}: missing host")
    q = {
        "info_hash": params.infohash,
        "peer_id": params.peer_id,
        "port": params.port,
        "uploaded": params.uploaded,
        "downloaded": params.downloaded,
        "left": params.left,
        "compact": 1,
        "numwant": 100,
    }
    if event:
        q["event"] = event
    sep = "&" if p.query else "?"
    full = url + sep + urllib.parse.urlencode(q, quote_via=urllib.parse.quote)
    req = urllib.request.Request(full, headers={"User-Agent": "anydm"})
    deadline = time.monotonic() + timeout * 1.5  # `timeout` alone limits each read, not a slow trickle
    body = bytearray()
    with urllib.request.build_opener(_Redirects, _VettedHTTP, _VettedHTTPS).open(req, timeout=timeout) as resp:
        while chunk := resp.read1(16384):
            body += chunk
            if len(body) > MAX_RESPONSE:
                raise TrackerError(f"{_where(url)}: response too large")
            if time.monotonic() > deadline:
                raise TrackerError(f"{_where(url)}: response too slow")
    try:
        return _parse_http(bytes(body))
    except TrackerError as e:
        raise TrackerError(f"{_where(url)}: {e}") from None


async def _in_daemon_thread[T](fn: Callable[..., T], *args: object) -> T:
    """Like asyncio.to_thread, but a thread stuck in a slow network call cannot hold the process up at exit."""
    loop = asyncio.get_running_loop()
    fut: asyncio.Future[T] = loop.create_future()

    def settle(result: T | None, error: BaseException | None) -> None:
        if not fut.cancelled():
            if error is not None:
                fut.set_exception(error)
            else:
                fut.set_result(result)  # ty: ignore[invalid-argument-type]

    def run() -> None:
        try:
            result, error = fn(*args), None
        except BaseException as e:
            result, error = None, e
        with contextlib.suppress(RuntimeError):  # the loop is gone
            loop.call_soon_threadsafe(settle, result, error)

    threading.Thread(target=run, daemon=True).start()
    return await fut


class _Udp(asyncio.DatagramProtocol):
    def __init__(self) -> None:
        self.queue: asyncio.Queue[bytes] = asyncio.Queue()

    def datagram_received(self, data: bytes, addr: object) -> None:
        self.queue.put_nowait(data)

    def error_received(self, exc: Exception) -> None:
        pass


async def _udp(url: str, params: AnnounceParams, event: str | None, timeout: float, retries: int) -> AnnounceResult:
    p = urllib.parse.urlsplit(url)
    host, port = p.hostname, p.port  # `.port` raises ValueError for a bad port
    if not host or not port:
        raise TrackerError(f"{_where(url)}: missing host or port")
    loop = asyncio.get_running_loop()
    try:
        ips = await _in_daemon_thread(_vet_host, url, host, port, p.path)
    except OSError:
        raise TrackerError(f"{_where(url)}: cannot resolve") from None
    try:
        transport, proto = await loop.create_datagram_endpoint(_Udp, remote_addr=(ips[0], port))
    except OSError as e:
        raise TrackerError(f"{_where(url)}: {_reason(e)}") from None

    async def exchange(build: bytes, want_action: int, tid: int) -> bytes:
        for _ in range(retries):
            transport.sendto(build)
            try:
                async with asyncio.timeout(timeout):
                    while True:
                        data = await proto.queue.get()
                        if len(data) >= 8 and struct.unpack(">II", data[:8])[1] == tid:
                            break
            except TimeoutError:
                continue
            action = struct.unpack(">I", data[:4])[0]
            if action == 3:
                raise TrackerError(f"{_where(url)}: {_clean(data[8:].decode(errors='replace'), 200)}")
            if action != want_action:
                raise TrackerError(f"{_where(url)}: unexpected reply")
            return data
        raise TrackerError(f"{_where(url)}: no reply")

    try:
        sock = transport.get_extra_info("socket")
        v6 = sock is not None and sock.family == socket.AF_INET6  # BEP 15: 18-byte entries over IPv6
        tid = random.getrandbits(32)
        data = await exchange(struct.pack(">QII", UDP_MAGIC, 0, tid), 0, tid)
        if len(data) < 16:
            raise TrackerError(f"{_where(url)}: short reply")
        conn_id = struct.unpack(">Q", data[8:16])[0]
        tid = random.getrandbits(32)
        ev = {None: 0, "completed": 1, "started": 2, "stopped": 3}[event]
        req = (
            struct.pack(">QII", conn_id, 1, tid)
            + params.infohash
            + params.peer_id
            + struct.pack(">QQQIIIiH", params.downloaded, params.left, params.uploaded, ev, 0, random.getrandbits(32), -1, params.port)
        )
        data = await exchange(req, 1, tid)
        if len(data) < 20:
            raise TrackerError(f"{_where(url)}: short reply")
        interval = struct.unpack(">I", data[8:12])[0]
        return AnnounceResult(interval or DEFAULT_INTERVAL, _compact(data[20:], v6))
    finally:
        transport.close()


async def announce(
    url: str, params: AnnounceParams, event: str | None = None, timeout: float = 15.0, retries: int = 2
) -> AnnounceResult:
    """One tracker. Whatever goes wrong, including a malformed URL, comes out as a TrackerError."""
    try:
        scheme = urllib.parse.urlsplit(url).scheme
        if scheme in ("http", "https"):
            return await _in_daemon_thread(_http_get, url, params, event, timeout)
        if scheme == "udp":
            return await _udp(url, params, event, timeout, retries)
        raise TrackerError(f"{_where(url)}: unsupported tracker scheme")
    except TrackerError:
        raise
    except Exception as e:  # CancelledError is a BaseException and passes through
        raise TrackerError(f"{_where(url)}: {_reason(e)}") from None


async def _first_answer(
    urls: list[str], params: AnnounceParams, event: str | None, timeout: float
) -> AnnounceResult:
    """Ask the trackers of one tier at once; the first good answer wins and the rest are cancelled."""
    sem = asyncio.Semaphore(TIER_CONCURRENCY)

    async def one(url: str) -> AnnounceResult:
        async with sem:
            return await announce(url, params, event, timeout)

    tasks = [asyncio.create_task(one(u)) for u in urls]
    last: TrackerError | None = None
    pending: set[asyncio.Task[AnnounceResult]] = set(tasks)
    try:
        while pending:
            done, pending = await asyncio.wait(pending, return_when=asyncio.FIRST_COMPLETED)
            for t in done:
                try:
                    return t.result()
                except TrackerError as e:
                    last = e
                except Exception as e:  # announce() should have converted it; never let one tracker end the tier
                    last = TrackerError(type(e).__name__)
    finally:
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    raise last or TrackerError("the tier has no trackers")


async def announce_tiers(
    tiers: list[list[str]],
    params: AnnounceParams,
    event: str | None = None,
    timeout: float = 15.0,
    overall: float = 60.0,
) -> AnnounceResult:
    """The first answer wins: tiers are tried in order (BEP 12), the trackers of one tier together. After
    `overall` seconds no further tier is started."""
    last: TrackerError | None = None
    deadline = time.monotonic() + overall
    for tier in tiers:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            return await asyncio.wait_for(_first_answer(tier, params, event, timeout), remaining)
        except TrackerError as e:
            last = e
        except TimeoutError:
            last = TrackerError("trackers did not answer in time")
    raise last or TrackerError("the torrent has no trackers")
