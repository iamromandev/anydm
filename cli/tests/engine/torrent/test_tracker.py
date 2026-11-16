import socket
import threading
import time

import pytest
from src.engine.torrent import tracker as tracker_mod
from src.engine.torrent.tracker import AnnounceParams, TrackerError, announce, announce_tiers

from tests.engine.torrent.trackers import HttpTracker, RawTcpServer, UdpTracker

PARAMS = AnnounceParams(infohash=bytes(range(20)), peer_id=b"-AD0001-123456789012", port=6881, left=1000)
PEERS = [("127.0.0.1", 5001), ("10.0.0.2", 5002)]


@pytest.fixture
def http():
    t = HttpTracker(PEERS).start()
    yield t
    t.stop()


@pytest.fixture
def udp():
    t = UdpTracker(PEERS).start()
    yield t
    t.stop()


async def test_http_announce_returns_compact_peers_and_interval(http: HttpTracker) -> None:
    r = await announce(http.url, PARAMS, event="started")
    assert r.peers == PEERS
    assert r.interval == 1800
    q = http.queries[0]
    assert q["info_hash"][0].encode("latin-1") == PARAMS.infohash
    assert q["peer_id"][0].encode("latin-1") == PARAMS.peer_id
    assert q["port"] == ["6881"] and q["left"] == ["1000"] and q["compact"] == ["1"]
    assert q["event"] == ["started"]


async def test_http_announce_accepts_dictionary_peers() -> None:
    t = HttpTracker(PEERS, dict_peers=True).start()
    try:
        assert (await announce(t.url, PARAMS)).peers == PEERS
    finally:
        t.stop()


async def test_http_failure_reason_becomes_an_error_without_leaking_the_url() -> None:
    t = HttpTracker([], failure="nope").start()
    try:
        with pytest.raises(TrackerError) as e:
            await announce(t.url.replace("/announce", "/announce?passkey=SECRET"), PARAMS)
        assert "nope" in str(e.value)
        assert "SECRET" not in str(e.value)
    finally:
        t.stop()


async def test_udp_announce_returns_peers(udp: UdpTracker) -> None:
    r = await announce(udp.url, PARAMS, event="started")
    assert r.peers == PEERS
    assert r.interval == 1800
    assert udp.announces[0][16:36] == PARAMS.infohash


async def test_udp_announce_retries_lost_datagrams() -> None:
    t = UdpTracker(PEERS, drop_first=1).start()
    try:
        assert (await announce(t.url, PARAMS, timeout=0.2)).peers == PEERS
    finally:
        t.stop()


async def test_udp_announce_times_out_when_silent() -> None:
    t = UdpTracker(PEERS, drop_first=99).start()
    try:
        with pytest.raises(TrackerError):
            await announce(t.url, PARAMS, timeout=0.1, retries=1)
    finally:
        t.stop()


async def test_unsupported_scheme_is_an_error() -> None:
    with pytest.raises(TrackerError):
        await announce("wss://x/announce", PARAMS)


async def test_tiers_fall_through_to_the_next_working_tracker(http: HttpTracker) -> None:
    dead = HttpTracker([], failure="down").start()
    try:
        r = await announce_tiers([[dead.url], [http.url]], PARAMS)
        assert r.peers == PEERS
    finally:
        dead.stop()


async def test_all_trackers_failing_raises(http: HttpTracker) -> None:
    http.failure = "busy"
    with pytest.raises(TrackerError):
        await announce_tiers([[http.url]], PARAMS)


@pytest.mark.parametrize(
    "url",
    [
        "http://[bad/announce?passkey=SECRET",
        "udp://127.0.0.1:99999/announce?passkey=SECRET",
        "udp://127.0.0.1/announce?passkey=SECRET",
        "http://127.0.0.1:notaport/announce?passkey=SECRET",
        "http://127.0.0.1:99999/announce?passkey=SECRET",
        "http://\u0000/announce?passkey=SECRET",
        "ht\ttp://x/announce?passkey=SECRET",
        "http:///announce?passkey=SECRET",
        "udp://:80/announce?passkey=SECRET",
    ],
)
async def test_malformed_tracker_urls_become_a_tracker_error_without_the_passkey(url: str) -> None:
    with pytest.raises(TrackerError) as e:
        await announce(url, PARAMS, timeout=0.2, retries=1)
    assert "SECRET" not in str(e.value) and "passkey" not in str(e.value)


@pytest.mark.parametrize("reply", [b"garbage\r\n\r\n", b"", b"HTTP/1.1 200 OK\r\nContent-Length: nope\r\n\r\n", b"HTTP/9.9 xyz\r\n\r\n"])
async def test_http_protocol_errors_become_a_tracker_error_without_the_passkey(reply: bytes) -> None:
    srv = RawTcpServer(reply).start()
    try:
        with pytest.raises(TrackerError) as e:
            await announce(srv.url, PARAMS, timeout=2)
        assert "SECRET" not in str(e.value)
    finally:
        srv.stop()


def test_where_cannot_raise_and_hides_the_path_and_control_characters() -> None:
    assert tracker_mod._where("http://[bad/announce?passkey=SECRET") == "?"
    assert tracker_mod._where(None) == "?"
    shown = tracker_mod._where("http://ho\x1b[2Jst.example/announce?passkey=SECRET")
    assert "\x1b" not in shown and "SECRET" not in shown and "announce" not in shown
    assert tracker_mod._where("http://example.org:80/a/b?passkey=SECRET") == "http://example.org"


async def test_an_oversized_http_response_is_refused() -> None:
    t = HttpTracker([], raw_body=b"d8:intervali1800e5:peers" + b"6000000:" + b"x" * (tracker_mod.MAX_RESPONSE + 10) + b"e").start()
    try:
        with pytest.raises(TrackerError, match="too large"):
            await announce(t.url, PARAMS)
    finally:
        t.stop()


async def test_a_response_that_drips_forever_is_cut_off_by_a_deadline() -> None:
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    stop = threading.Event()

    def drip() -> None:
        conn, _ = srv.accept()
        conn.recv(4096)
        conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n")
        while not stop.is_set():
            try:
                conn.sendall(b"x")
            except OSError:
                return
            time.sleep(0.1)

    threading.Thread(target=drip, daemon=True).start()
    try:
        start = time.monotonic()
        with pytest.raises(TrackerError):
            await announce(f"http://127.0.0.1:{srv.getsockname()[1]}/announce", PARAMS, timeout=0.5)
        assert time.monotonic() - start < 3
    finally:
        stop.set()
        srv.close()


async def test_up_to_three_redirects_are_followed() -> None:
    t = HttpTracker(PEERS, redirects={"/r0/announce": "/r1/announce", "/r1/announce": "/r2/announce", "/r2/announce": "/announce"}).start()
    try:
        assert (await announce(t.url.replace("/announce", "/r0/announce"), PARAMS)).peers == PEERS
    finally:
        t.stop()


async def test_a_fourth_redirect_is_refused() -> None:
    redirects = {"/r0/announce": "/r1/announce", "/r1/announce": "/r2/announce", "/r2/announce": "/r3/announce", "/r3/announce": "/announce"}
    t = HttpTracker(PEERS, redirects=redirects).start()
    try:
        with pytest.raises(TrackerError):
            await announce(t.url.replace("/announce", "/r0/announce"), PARAMS)
    finally:
        t.stop()


@pytest.mark.parametrize("target", ["file:///etc/passwd", "ftp://127.0.0.1/x", "data:text/plain,hi", "gopher://127.0.0.1/"])
async def test_redirects_to_other_schemes_are_refused(target: str) -> None:
    t = HttpTracker(PEERS, redirects={"/announce": target}).start()
    try:
        with pytest.raises(TrackerError):
            await announce(t.url, PARAMS)
    finally:
        t.stop()


async def test_dictionary_peers_keep_only_real_addresses() -> None:
    entries: list[dict[bytes, object]] = [
        {b"ip": b"evil.example.com", b"port": 80},
        {b"ip": b"10.0.0.1", b"port": 6881},
        {b"ip": b"999.1.1.1", b"port": 6881},
        {b"ip": b"::1", b"port": 6882},
        {b"ip": b"10.0.0.2", b"port": 0},
        {b"ip": b"10.0.0.3", b"port": 70000},
        {b"ip": b"10.0.0.4", b"port": b"80"},
        {b"ip": b"\xff\xfe", b"port": 5},
    ]
    t = HttpTracker([], raw_peers=entries).start()
    try:
        assert (await announce(t.url, PARAMS)).peers == [("10.0.0.1", 6881), ("::1", 6882)]
    finally:
        t.stop()


async def test_compact_peers_with_port_zero_are_dropped() -> None:
    t = HttpTracker([("10.0.0.1", 0), ("10.0.0.2", 7)]).start()
    try:
        assert (await announce(t.url, PARAMS)).peers == [("10.0.0.2", 7)]
    finally:
        t.stop()


async def test_min_interval_is_reported() -> None:
    t = HttpTracker(PEERS, interval=900, min_interval=600).start()
    try:
        r = await announce(t.url, PARAMS)
        assert (r.interval, r.min_interval) == (900, 600)
    finally:
        t.stop()


async def test_udp_reply_with_the_wrong_transaction_id_is_ignored() -> None:
    t = UdpTracker(PEERS, mode="wrong_tid").start()
    try:
        with pytest.raises(TrackerError, match="no reply"):
            await announce(t.url, PARAMS, timeout=0.1, retries=2)
    finally:
        t.stop()


async def test_udp_error_action_becomes_a_tracker_error_with_its_message() -> None:
    t = UdpTracker(PEERS, mode="error").start()
    try:
        with pytest.raises(TrackerError, match="go away"):
            await announce(t.url, PARAMS, timeout=0.5)
    finally:
        t.stop()


async def test_udp_over_ipv6_reads_18_byte_peer_entries() -> None:
    peers = [("::1", 6881), ("2001:db8::7", 51413)]
    try:
        t = UdpTracker(peers, host="::1").start()
    except OSError:
        pytest.skip("no IPv6 loopback")
    try:
        assert (await announce(t.url, PARAMS, timeout=1)).peers == peers
    finally:
        t.stop()


async def test_a_failing_tier_followed_by_a_good_one_still_answers(http: HttpTracker) -> None:
    bad = RawTcpServer(b"garbage\r\n\r\n").start()
    try:
        r = await announce_tiers([["udp://127.0.0.1:99999/x?passkey=SECRET"], [bad.url], ["http://[bad/x"], [http.url]], PARAMS)
        assert r.peers == PEERS
    finally:
        bad.stop()


async def test_dead_trackers_in_a_tier_do_not_delay_a_live_one(http: HttpTracker) -> None:
    silent = [UdpTracker(PEERS, drop_first=99).start() for _ in range(8)]
    try:
        start = time.monotonic()
        r = await announce_tiers([[*(s.url for s in silent), http.url]], PARAMS, timeout=0.5)
        assert r.peers == PEERS
        assert time.monotonic() - start < 1.0  # one after the other this would take 8 x 2 x 0.5 s
    finally:
        for s in silent:
            s.stop()


async def test_all_trackers_failing_raises_a_tracker_error_without_the_passkey() -> None:
    with pytest.raises(TrackerError) as e:
        await announce_tiers([["udp://127.0.0.1:99999/x?passkey=SECRET"], ["http://[bad/x?passkey=SECRET"]], PARAMS)
    assert "SECRET" not in str(e.value)


# --- S8: trackers on local addresses (SSRF) ---


def fake_dns(monkeypatch: pytest.MonkeyPatch, mapping: dict[str, list[str]]) -> list[str]:
    """Resolve the given names to the given addresses; everything else as usual. Returns the names asked for."""
    asked: list[str] = []
    real = socket.getaddrinfo

    def getaddrinfo(host, port, *args, **kw):
        if isinstance(host, str) and host in mapping:
            asked.append(host)
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, port)) for ip in mapping[host]]
        return real(host, port, *args, **kw)

    monkeypatch.setattr(socket, "getaddrinfo", getaddrinfo)
    return asked


async def test_a_local_tracker_whose_path_ends_in_announce_is_allowed(http: HttpTracker) -> None:
    assert http.url.endswith("/announce")
    assert (await announce(http.url, PARAMS)).peers == PEERS


async def test_a_local_url_that_is_not_an_announce_url_is_skipped_without_any_request(http: HttpTracker) -> None:
    url = http.url.replace("/announce", "/admin/restart?passkey=SECRET")
    with pytest.raises(TrackerError) as e:
        await announce(url, PARAMS)
    assert "skipped" in str(e.value) and "SECRET" not in str(e.value) and "/admin" not in str(e.value)
    assert http.queries == []


async def test_local_trackers_with_any_path_are_allowed_by_the_opt_in_variable(http: HttpTracker, monkeypatch) -> None:
    monkeypatch.setenv("ANYDM_ALLOW_LOCAL_TRACKERS", "1")
    r = await announce(http.url.replace("/announce", "/anything"), PARAMS)
    assert r.peers == PEERS and len(http.queries) == 1


async def test_the_opt_in_variable_means_exactly_one(http: HttpTracker, monkeypatch) -> None:
    monkeypatch.setenv("ANYDM_ALLOW_LOCAL_TRACKERS", "0")
    with pytest.raises(TrackerError, match="skipped"):
        await announce(http.url.replace("/announce", "/anything"), PARAMS)


@pytest.mark.parametrize("address", ["127.0.0.1", "10.1.2.3", "192.168.0.9", "169.254.169.254", "0.0.0.0"])
async def test_a_name_that_resolves_to_a_local_address_is_skipped_unless_it_is_an_announce_url(monkeypatch, address: str) -> None:
    fake_dns(monkeypatch, {"tracker.example": [address]})
    with pytest.raises(TrackerError, match="skipped"):
        await announce("http://tracker.example:1/stats", PARAMS, timeout=2)


async def test_every_resolved_address_is_checked_not_just_the_first(monkeypatch) -> None:
    fake_dns(monkeypatch, {"tracker.example": ["93.184.216.34", "127.0.0.1"]})
    with pytest.raises(TrackerError, match="skipped"):
        await announce("http://tracker.example:1/stats", PARAMS, timeout=2)


async def test_the_name_is_resolved_once_and_the_connection_goes_to_the_address_that_was_checked(http: HttpTracker, monkeypatch) -> None:
    asked = fake_dns(monkeypatch, {"tracker.example": ["127.0.0.1"]})
    url = f"http://tracker.example:{http.port}/announce"
    assert (await announce(url, PARAMS)).peers == PEERS
    assert asked == ["tracker.example"]


async def test_a_redirect_to_a_local_url_that_is_not_an_announce_url_is_refused() -> None:
    t = HttpTracker(PEERS).start()
    try:
        t.redirects["/announce"] = f"http://127.0.0.1:{t.port}/admin"
        with pytest.raises(TrackerError, match="skipped"):
            await announce(t.url, PARAMS)
        assert len(t.queries) == 1  # the first request only
    finally:
        t.stop()


async def test_a_local_udp_url_that_is_not_an_announce_url_is_skipped(udp: UdpTracker) -> None:
    with pytest.raises(TrackerError, match="skipped"):
        await announce(udp.url.replace("/announce", "/other"), PARAMS, timeout=0.3, retries=1)
    assert udp.announces == []


async def test_a_udp_announce_url_on_a_local_address_is_allowed(udp: UdpTracker) -> None:
    assert (await announce(udp.url, PARAMS)).peers == PEERS


async def test_a_tier_of_skipped_and_good_trackers_still_answers(http: HttpTracker) -> None:
    bad = http.url.replace("/announce", "/other")
    r = await announce_tiers([[bad, http.url]], PARAMS)
    assert r.peers == PEERS
