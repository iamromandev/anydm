import asyncio
import os
import socket
import time

import pytest
from src.engine.torrent import dht as dht_mod
from src.engine.torrent.dht import DhtClient, DhtError, bootstrap_from_env, dht_peers_loop, parse_nodes, parse_peers

from tests.engine.torrent.fake_dht import FakeNode, Gauge, chain, compact_node, compact_peer, near
from tests.engine.torrent.helpers import Recorder

IH = os.urandom(20)
PEERS = [("127.0.0.1", 40001), ("127.0.0.1", 40002)]


@pytest.fixture
async def net():
    nodes: list[FakeNode] = []
    yield nodes
    for n in nodes:
        n.stop()


def client(nodes: list[FakeNode], **kw) -> DhtClient:
    kw.setdefault("timeout", 0.5)
    return DhtClient([nodes[0].addr], **kw)


# --- compact formats ---


def test_compact_ipv4_and_ipv6_peers_are_parsed() -> None:
    blob4 = compact_peer(("93.184.216.34", 6881))
    blob6 = compact_peer(("2606:2800:220:1::1", 51413))
    assert parse_peers([blob4, blob6], allow_local=False) == [("93.184.216.34", 6881), ("2606:2800:220:1::1", 51413)]


def test_malformed_peers_zero_ports_and_non_bytes_are_dropped() -> None:
    good = compact_peer(("93.184.216.34", 80))
    values = [good, b"short", b"x" * 7, compact_peer(("93.184.216.35", 0)), 5, [b"x"], {}]
    assert parse_peers(values, allow_local=False) == [("93.184.216.34", 80)]
    assert parse_peers(b"not a list", allow_local=False) == []


def test_private_and_loopback_peers_are_ignored_unless_local_is_allowed() -> None:
    values = [compact_peer(a) for a in [("10.0.0.5", 1), ("127.0.0.1", 2), ("192.168.1.1", 3), ("169.254.1.1", 4), ("0.0.0.0", 5), ("224.0.0.1", 6), ("::1", 7), ("fe80::1", 8)]]
    assert parse_peers(values, allow_local=False) == []
    assert ("127.0.0.1", 2) in parse_peers(values, allow_local=True)
    assert ("224.0.0.1", 6) not in parse_peers(values, allow_local=True)  # multicast is never a peer


def test_peer_counts_are_capped() -> None:
    values = [compact_peer(("93.184.216.34", p)) for p in range(1, 2000)]
    assert len(parse_peers(values, allow_local=False)) == dht_mod.MAX_VALUES


def test_compact_nodes_are_parsed_filtered_and_capped() -> None:
    blob = b"".join(compact_node(os.urandom(20), (f"93.184.216.{i % 250 + 1}", 1000 + i)) for i in range(500))
    blob += compact_node(os.urandom(20), ("10.0.0.1", 9)) + b"trailing"
    nodes = parse_nodes(blob, allow_local=False)
    assert len(nodes) == dht_mod.MAX_NODES
    assert all(addr[0].startswith("93.184.") for _, addr in nodes)


def test_compact_ipv6_nodes_are_parsed() -> None:
    nid = os.urandom(20)
    blob = nid + socket.inet_pton(socket.AF_INET6, "2606:2800:220:1::1") + (6881).to_bytes(2, "big")
    assert parse_nodes(blob, allow_local=False, v6=True) == [(nid, ("2606:2800:220:1::1", 6881))]


def test_bootstrap_is_configurable_from_the_environment() -> None:
    assert bootstrap_from_env({}) == list(dht_mod.DEFAULT_BOOTSTRAP)
    assert ("router.bittorrent.com", 6881) in dht_mod.DEFAULT_BOOTSTRAP
    assert ("dht.transmissionbt.com", 6881) in dht_mod.DEFAULT_BOOTSTRAP
    assert ("router.utorrent.com", 6881) in dht_mod.DEFAULT_BOOTSTRAP
    assert bootstrap_from_env({"ANYDM_DHT_BOOTSTRAP": "127.0.0.1:7000, example.org:80,bad,host:99999,:5"}) == [
        ("127.0.0.1", 7000),
        ("example.org", 80),
    ]
    assert bootstrap_from_env({"ANYDM_DHT_BOOTSTRAP": ""}) == []


def test_all_local_bootstrap_allows_local_peers_but_one_public_node_does_not() -> None:
    assert dht_mod.all_local(["127.0.0.1", "10.1.2.3"]) is True
    assert dht_mod.all_local(["127.0.0.1", "93.184.216.34"]) is False
    assert dht_mod.all_local([]) is False


# --- queries ---


async def test_ping_returns_the_node_id(net) -> None:
    net.append(await FakeNode(os.urandom(20)).start())
    async with client(net) as c:
        assert await c.ping(net[0].addr) == net[0].node_id
    assert net[0].queries[0][0] == b"ping"


async def test_find_node_returns_the_nodes_it_knows(net) -> None:
    other = (os.urandom(20), ("127.0.0.1", 41000))
    net.append(await FakeNode(os.urandom(20), neighbours=[other]).start())
    async with client(net, allow_local=True) as c:
        assert await c.find_node(net[0].addr, IH) == [other]
    assert net[0].queries[0][1][b"target"] == IH


async def test_get_peers_returns_peers_nodes_and_the_token(net) -> None:
    net.append(await FakeNode(os.urandom(20), peers=PEERS, neighbours=[(os.urandom(20), ("127.0.0.1", 41000))]).start())
    async with client(net, allow_local=True) as c:
        r = await c.get_peers(net[0].addr, IH)
    assert r.peers == PEERS and r.token == net[0].token and len(r.nodes) == 1


async def test_a_silent_node_is_a_timeout_error(net) -> None:
    net.append(await FakeNode(os.urandom(20), mode="silent").start())
    async with client(net, timeout=0.1) as c:
        with pytest.raises(DhtError):
            await c.ping(net[0].addr)


async def test_outgoing_queries_are_rate_limited(net) -> None:
    net.append(await FakeNode(os.urandom(20)).start())
    async with client(net, rate=20.0) as c:
        start = time.monotonic()
        await asyncio.gather(*(c.ping(net[0].addr) for _ in range(11)))
        assert time.monotonic() - start >= 0.45  # eleven queries at 20 per second
    assert len(net[0].queries) == 11


# --- the iterative lookup ---


async def test_lookup_walks_a_chain_of_nodes_and_finds_the_peers(net) -> None:
    net += await chain(IH, PEERS, hops=3)
    got: list = []
    async with client(net) as c:
        res = await c.lookup(IH, got.extend)
    assert set(res.peers) == set(PEERS) and set(got) == set(PEERS)
    assert all(len(n.queries) >= 1 for n in net)  # it walked all the way
    assert res.tokens[net[-1].addr] == net[-1].token  # the token is kept
    assert all(q != b"announce_peer" for n in net for q, _ in n.queries)  # and never used


async def test_lookup_asks_get_peers_for_the_infohash(net) -> None:
    net += await chain(IH, PEERS, hops=1)
    async with client(net) as c:
        await c.lookup(IH)
    assert net[0].queries[0][0] == b"get_peers" and net[0].queries[0][1][b"info_hash"] == IH


async def test_lookup_runs_at_most_three_queries_at_a_time(net) -> None:
    gauge = Gauge()
    others = [await FakeNode(os.urandom(20), delay=0.05, gauge=gauge).start() for _ in range(12)]
    net += [await FakeNode(near(IH, 100), neighbours=[(o.node_id, o.addr) for o in others]).start(), *others]
    async with client(net) as c:
        await c.lookup(IH)
    assert gauge.peak == dht_mod.ALPHA == 3
    assert dht_mod.K == 8
    assert sum(1 for n in others if n.queries) >= 7


async def test_lookup_stops_when_the_closest_nodes_have_all_answered(net) -> None:
    far = [await FakeNode(near(IH, 159 - i)).start() for i in range(30)]
    near_nodes = [await FakeNode(near(IH, i)).start() for i in range(8)]
    for n in near_nodes:
        n.neighbours = [(f.node_id, f.addr) for f in far]
    net += [await FakeNode(near(IH, 120), neighbours=[(n.node_id, n.addr) for n in near_nodes]).start(), *near_nodes, *far]
    async with client(net) as c:
        await c.lookup(IH)
    assert all(n.queries for n in near_nodes)
    assert sum(1 for n in far if n.queries) < 30  # no need to ask the distant ones once eight closer ones answered


async def test_a_lookup_with_no_bootstrap_nodes_returns_nothing_at_once() -> None:
    async with DhtClient([]) as c:
        res = await asyncio.wait_for(c.lookup(IH), 1)
    assert res.peers == [] and res.responded == 0


async def test_an_unresolvable_bootstrap_name_is_skipped() -> None:
    async with DhtClient([("nonexistent.invalid", 6881)], timeout=0.2) as c:
        res = await asyncio.wait_for(c.lookup(IH), 5)
    assert res.peers == []


# --- hostile nodes ---


@pytest.mark.parametrize("mode", ["junk", "wrong_tid", "spoof", "huge", "deep", "no_id", "error", "silent"])
async def test_a_hostile_node_does_not_stop_the_lookup(net, mode: str) -> None:
    honest = await chain(IH, PEERS, hops=2)
    bad = await FakeNode(near(IH, 5), mode=mode).start()  # it looks like the best node there is
    honest[0].neighbours.append((bad.node_id, bad.addr))
    net += [*honest, bad]
    async with client(net, timeout=0.15) as c:
        res = await asyncio.wait_for(c.lookup(IH), 20)
    assert set(res.peers) == set(PEERS)
    assert bad.addr not in res.tokens


async def test_a_wrong_transaction_id_is_not_accepted_as_an_answer(net) -> None:
    net.append(await FakeNode(os.urandom(20), mode="wrong_tid").start())
    async with client(net, timeout=0.15) as c:
        with pytest.raises(DhtError):
            await c.ping(net[0].addr)


async def test_an_answer_from_another_address_is_not_accepted(net) -> None:
    net.append(await FakeNode(os.urandom(20), mode="spoof").start())
    async with client(net, timeout=0.15) as c:
        with pytest.raises(DhtError):
            await c.ping(net[0].addr)


async def test_a_node_flood_is_capped(net, monkeypatch) -> None:
    monkeypatch.setattr(dht_mod, "MAX_QUERIES", 25)
    flooder = await FakeNode(near(IH, 10), mode="flood").start()
    net.append(flooder)
    async with client(net, timeout=0.05) as c:
        res = await asyncio.wait_for(c.lookup(IH), 20)
        assert c.queries_sent <= 25
    assert res.peers == []


async def test_peer_values_from_one_node_are_capped_and_filtered(net) -> None:
    values = [compact_peer(("93.184.216.34", p)) for p in range(1, 450)] + [compact_peer(("10.0.0.1", 5))]
    net.append(await FakeNode(os.urandom(20), raw_values=values).start())
    async with client(net, allow_local=False) as c:
        res = await c.lookup(IH)
    assert len(res.peers) == dht_mod.MAX_VALUES and ("10.0.0.1", 5) not in res.peers


async def test_loopback_peers_are_dropped_when_a_bootstrap_node_is_not_local(net) -> None:
    net.append(await FakeNode(os.urandom(20), peers=[("127.0.0.1", 5), ("93.184.216.34", 6)]).start())
    async with client(net, allow_local=False) as c:
        res = await c.lookup(IH)
    assert res.peers == [("93.184.216.34", 6)]


async def test_local_peers_are_kept_when_every_bootstrap_node_is_local(net) -> None:
    net += await chain(IH, PEERS, hops=1)
    async with client(net) as c:  # allow_local worked out from the (loopback) bootstrap node
        res = await c.lookup(IH)
    assert set(res.peers) == set(PEERS)


async def test_lookup_ends_within_its_time_limit_even_if_nodes_keep_answering_slowly(net) -> None:
    net.append(await FakeNode(os.urandom(20), delay=0.3, peers=PEERS).start())
    async with client(net, timeout=1.0) as c:
        start = time.monotonic()
        await c.lookup(IH, max_time=0.1)
        assert time.monotonic() - start < 0.6


# --- the loop that feeds a session ---


async def test_dht_peers_loop_feeds_peers_and_cancels_quickly(net) -> None:
    net += await chain(IH, PEERS, hops=2)
    got: list = []
    async with client(net) as c:
        task = asyncio.create_task(dht_peers_loop(c, IH, got.extend, Recorder()))
        deadline = time.monotonic() + 5
        while not got and time.monotonic() < deadline:
            await asyncio.sleep(0.02)
        assert set(got) == set(PEERS)
        start = time.monotonic()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert time.monotonic() - start < 1


async def test_dht_peers_loop_says_once_when_no_node_answers(net) -> None:
    net.append(await FakeNode(os.urandom(20), mode="silent").start())
    rec = Recorder()
    async with client(net, timeout=0.05) as c:
        task = asyncio.create_task(dht_peers_loop(c, IH, lambda a: None, rec, retry=0.05))
        await asyncio.sleep(0.6)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    assert len([n for n in rec.notices if n.startswith("dht:")]) == 1


async def test_dht_peers_loop_survives_a_failing_callback(net) -> None:
    net += await chain(IH, PEERS, hops=1)
    calls = []

    def boom(addrs) -> None:
        calls.append(addrs)
        raise RuntimeError("boom")

    async with client(net) as c:
        task = asyncio.create_task(dht_peers_loop(c, IH, boom, Recorder(), retry=0.05))
        await asyncio.sleep(0.5)
        assert not task.done()
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    assert len(calls) >= 2


# --- review fixes ---


def _fake_resolver(monkeypatch: pytest.MonkeyPatch, address: str, fail_first: int = 0) -> list[tuple]:
    """Make every host name resolve to `address` (after `fail_first` failures); returns the calls made."""
    calls: list[tuple] = []

    async def resolve(fn, *args):
        calls.append(args)
        if len(calls) <= fail_first:
            raise socket.gaierror("temporary failure in name resolution")
        return [(socket.AF_INET, socket.SOCK_DGRAM, 0, "", (address, args[1]))]

    monkeypatch.setattr(dht_mod, "_in_daemon_thread", resolve)
    return calls


async def test_a_name_that_failed_to_resolve_once_is_resolved_again_on_the_next_round(net, monkeypatch) -> None:
    net += await chain(IH, PEERS, hops=1)
    calls = _fake_resolver(monkeypatch, "127.0.0.1", fail_first=1)
    got: list = []
    async with DhtClient([("boot.example", net[0].port)], timeout=0.5) as c:
        task = asyncio.create_task(dht_peers_loop(c, IH, got.extend, Recorder(), retry=0.05))
        deadline = time.monotonic() + 5
        while not got and time.monotonic() < deadline:
            await asyncio.sleep(0.02)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    assert set(got) == set(PEERS) and len(calls) >= 2


async def test_a_resolution_that_worked_is_kept(net, monkeypatch) -> None:
    net += await chain(IH, PEERS, hops=1)
    calls = _fake_resolver(monkeypatch, "127.0.0.1")
    async with DhtClient([("boot.example", net[0].port)], timeout=0.5) as c:
        await c.lookup(IH)
        await c.lookup(IH)
    assert len(calls) == 1


async def test_local_peers_are_dropped_when_the_default_names_resolve_to_local_addresses(net, monkeypatch) -> None:
    node = await FakeNode(os.urandom(20), peers=[("127.0.0.1", 5), ("10.0.0.7", 6), ("93.184.216.34", 7)]).start()
    net.append(node)
    monkeypatch.setattr(dht_mod, "DEFAULT_BOOTSTRAP", (("router.example", node.port),))
    _fake_resolver(monkeypatch, "127.0.0.1")  # a poisoned or hostile resolver
    async with DhtClient(timeout=0.5) as c:
        res = await c.lookup(IH)
    assert res.responded == 1  # the node was asked, its answer was heard ...
    assert res.peers == [("93.184.216.34", 7)]  # ... and its local peers were not believed


async def test_local_peers_are_kept_for_an_explicit_all_local_bootstrap_that_is_a_name(net, monkeypatch) -> None:
    net += await chain(IH, PEERS, hops=1)
    _fake_resolver(monkeypatch, "127.0.0.1")
    async with DhtClient([("localnode.test", net[0].port)], timeout=0.5) as c:
        res = await c.lookup(IH)
    assert set(res.peers) == set(PEERS)


def test_the_zero_network_is_never_a_peer_even_when_local_is_allowed() -> None:
    values = [compact_peer(("0.1.2.3", 5)), compact_peer(("0.0.0.0", 6)), compact_peer(("127.0.0.1", 7))]
    assert parse_peers(values, allow_local=True) == [("127.0.0.1", 7)]
    blob = compact_node(os.urandom(20), ("0.9.9.9", 5))
    assert parse_nodes(blob, allow_local=True) == []


def test_a_bootstrap_of_the_zero_network_is_not_taken_for_a_local_one_either() -> None:
    assert dht_mod.all_local(["0.0.0.0"]) is False


async def test_a_datagram_from_an_address_we_asked_nothing_is_not_even_decoded(monkeypatch) -> None:
    proto = dht_mod._Proto()
    decoded: list[bytes] = []
    monkeypatch.setattr(dht_mod, "decode", lambda data, **kw: decoded.append(data))
    proto.datagram_received(b"d1:y1:ee", ("203.0.113.9", 6881))
    assert decoded == []
    proto.pending[b"abcd"] = (("203.0.113.9", 6881), asyncio.get_running_loop().create_future())
    proto.datagram_received(b"d1:y1:ee", ("203.0.113.9", 6881))
    assert decoded == [b"d1:y1:ee"]


async def test_transaction_ids_are_four_bytes(net) -> None:
    net.append(await FakeNode(os.urandom(20)).start())
    async with client(net) as c:
        await c.ping(net[0].addr)
        await c.ping(net[0].addr)
    assert [len(t) for t in net[0].tids] == [4, 4]
