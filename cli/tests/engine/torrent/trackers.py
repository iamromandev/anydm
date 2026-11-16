"""Local trackers for tests: HTTP (compact) and UDP (BEP 15)."""

import socket
import struct
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from src.engine.torrent.bencode import encode


def compact(peers: list[tuple[str, int]]) -> bytes:
    def one(h: str) -> bytes:
        return socket.inet_pton(socket.AF_INET6 if ":" in h else socket.AF_INET, h)

    return b"".join(one(h) + struct.pack(">H", p) for h, p in peers)


@dataclass
class HttpTracker:
    peers: list[tuple[str, int]]
    interval: int = 1800
    failure: str | None = None
    dict_peers: bool = False
    min_interval: int | None = None
    raw_peers: list[dict[bytes, object]] | None = None  # dictionary-form entries sent as they are
    raw_body: bytes | None = None  # answer with exactly this
    redirects: dict[str, str] = field(default_factory=dict)  # path -> Location
    queries: list[dict[str, list[str]]] = field(default_factory=list)
    raw_queries: list[str] = field(default_factory=list)
    port: int = 0
    stall: float = 0.0  # seconds to wait before answering, to play a tracker that has gone dead

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/announce"

    def start(self) -> HttpTracker:
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                outer.raw_queries.append(urlsplit(self.path).query)
                outer.queries.append(parse_qs(urlsplit(self.path).query, encoding="latin-1"))
                if outer.stall:
                    time.sleep(outer.stall)
                target = outer.redirects.get(urlsplit(self.path).path)
                if target:
                    self.send_response(302)
                    self.send_header("Location", target)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if outer.raw_body is not None:
                    body = outer.raw_body
                elif outer.raw_peers is not None:
                    body = encode({b"interval": outer.interval, b"peers": outer.raw_peers})
                elif outer.failure:
                    body = encode({b"failure reason": outer.failure.encode()})
                elif outer.dict_peers:
                    plist = [{b"ip": h.encode(), b"port": p, b"peer id": b"x" * 20} for h, p in outer.peers]
                    body = encode({b"interval": outer.interval, b"peers": plist})
                else:
                    d: dict[bytes, object] = {b"interval": outer.interval, b"peers": compact(outer.peers)}
                    if outer.min_interval is not None:
                        d[b"min interval"] = outer.min_interval
                    body = encode(d)
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format: str, *args: object) -> None:
                pass

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self._server.server_address[1]
        threading.Thread(target=self._server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True).start()
        return self

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()


@dataclass
class UdpTracker:
    peers: list[tuple[str, int]]
    interval: int = 1800
    drop_first: int = 0  # ignore this many requests, to exercise retries
    mode: str = "ok"  # "wrong_tid": answer with another transaction id; "error": answer every request with action 3
    host: str = "127.0.0.1"
    announces: list[bytes] = field(default_factory=list)
    port: int = 0

    @property
    def url(self) -> str:
        host = f"[{self.host}]" if ":" in self.host else self.host
        return f"udp://{host}:{self.port}/announce"

    def start(self) -> UdpTracker:
        self._sock = socket.socket(socket.AF_INET6 if ":" in self.host else socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.bind((self.host, 0))
        self.port = self._sock.getsockname()[1]
        threading.Thread(target=self._serve, daemon=True).start()
        return self

    def stop(self) -> None:
        self._sock.close()

    def _serve(self) -> None:
        conn_id = 0x1234567890ABCDEF
        while True:
            try:
                data, addr = self._sock.recvfrom(2048)
            except OSError:
                return
            if self.drop_first > 0:
                self.drop_first -= 1
                continue
            cid, action, tid = struct.unpack(">QII", data[:16])
            if self.mode == "wrong_tid":
                tid ^= 1
            if self.mode == "error":
                self._sock.sendto(struct.pack(">II", 3, tid) + b"go away", addr)
            elif action == 0 and cid == 0x41727101980:
                self._sock.sendto(struct.pack(">IIQ", 0, tid, conn_id), addr)
            elif action == 1 and cid == conn_id:
                self.announces.append(data)
                body = struct.pack(">IIIII", 1, tid, self.interval, 0, len(self.peers)) + compact(self.peers)
                self._sock.sendto(body, addr)
            else:
                self._sock.sendto(struct.pack(">II", 3, tid) + b"bad request", addr)


@dataclass
class RawTcpServer:
    """Accepts a connection, reads the request, answers with fixed bytes (maybe none) and hangs up."""

    reply: bytes
    port: int = 0

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/announce?passkey=SECRET"

    def start(self) -> RawTcpServer:
        self._sock = socket.socket()
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(8)
        self.port = self._sock.getsockname()[1]
        threading.Thread(target=self._serve, daemon=True).start()
        return self

    def stop(self) -> None:
        self._sock.close()

    def _serve(self) -> None:
        while True:
            try:
                conn, _ = self._sock.accept()
            except OSError:
                return
            with conn:
                conn.settimeout(2)
                try:
                    conn.recv(4096)
                    conn.sendall(self.reply)
                except OSError:
                    pass
