"""The session finds peers through the DHT too: always for magnets, otherwise only when no tracker can help."""

import asyncio
import os
import time
from pathlib import Path

import pytest
from src.core.progress import NullReporter
from src.engine.torrent import session as session_mod
from src.engine.torrent.dht import DhtClient
from src.engine.torrent.session import Session, waiting_message
from src.engine.torrent.storage import Storage

from tests.engine.torrent.fake_dht import FakeNode, chain
from tests.engine.torrent.helpers import Recorder
from tests.engine.torrent.seeding import make_seeder
from tests.engine.torrent.trackers import HttpTracker

FILES = {"a.bin": os.urandom(70000), "d/b.bin": os.urandom(100000)}


@pytest.fixture
async def net():
    nodes: list[FakeNode] = []
    yield nodes
    for n in nodes:
        n.stop()


async def run_session(swarm, dest: Path, net, dht_always: bool = False, report=None, timeout: float = 20.0) -> Session:
    storage = Storage(swarm.torrent, dest)
    dht = DhtClient([net[0].addr], timeout=0.3)
    s = Session(swarm.torrent, storage, set(), report or NullReporter(), b"-AD0001-abcdefghijkl", dht=dht, dht_always=dht_always)
    try:
        await asyncio.wait_for(s.run(), timeout)
    finally:
        storage.close()
        dht.close()
    return s


def same(dest: Path) -> bool:
    return all((dest / "pack" / rel).read_bytes() == data for rel, data in FILES.items())


def asked(net) -> int:
    return sum(len(n.queries) for n in net)


async def test_a_torrent_with_no_trackers_finds_its_peer_through_the_dht(tmp_path: Path, net) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=None)
    try:
        net += await chain(swarm.torrent.infohash, [("127.0.0.1", swarm.seeder.port)], hops=2)
        assert swarm.torrent.tiers == []
        await run_session(swarm, tmp_path / "dl", net)
        assert same(tmp_path / "dl")
    finally:
        await swarm.seeder.stop()


async def test_with_every_tracker_dead_the_dht_is_the_fallback(tmp_path: Path, net) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, announce="http://127.0.0.1:1/announce")
    try:
        net += await chain(swarm.torrent.infohash, [("127.0.0.1", swarm.seeder.port)], hops=1)
        await run_session(swarm, tmp_path / "dl", net)
        assert same(tmp_path / "dl")
    finally:
        await swarm.seeder.stop()


async def test_a_working_tracker_means_the_dht_is_not_asked(tmp_path: Path, net) -> None:
    tracker = HttpTracker([]).start()
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=tracker.url)
    tracker.peers = [("127.0.0.1", swarm.seeder.port)]
    try:
        net += await chain(swarm.torrent.infohash, [], hops=1)
        await run_session(swarm, tmp_path / "dl", net)
        assert same(tmp_path / "dl")
        assert asked(net) == 0
    finally:
        await swarm.seeder.stop()
        tracker.stop()


async def test_dht_always_asks_even_with_a_working_tracker(tmp_path: Path, net) -> None:
    tracker = HttpTracker([]).start()
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=tracker.url)
    tracker.peers = [("127.0.0.1", swarm.seeder.port)]
    try:
        net += await chain(swarm.torrent.infohash, [], hops=1)
        await run_session(swarm, tmp_path / "dl", net, dht_always=True)
        assert asked(net) > 0
    finally:
        await swarm.seeder.stop()
        tracker.stop()


async def test_a_private_torrent_never_uses_the_dht(tmp_path: Path, net) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=None, private=True)
    try:
        net += await chain(swarm.torrent.infohash, [("127.0.0.1", swarm.seeder.port)], hops=1)
        storage = Storage(swarm.torrent, tmp_path / "dl")
        dht = DhtClient([net[0].addr], timeout=0.3)
        s = Session(swarm.torrent, storage, set(), NullReporter(), b"-AD0001-abcdefghijkl", dht=dht, dht_always=True)
        task = asyncio.create_task(s.run())
        await asyncio.sleep(0.6)
        assert not task.done() and asked(net) == 0
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        storage.close()
        dht.close()
    finally:
        await swarm.seeder.stop()


async def test_dht_peers_obey_the_same_pending_bound_as_tracker_peers(tmp_path: Path, net, monkeypatch) -> None:
    monkeypatch.setattr(session_mod, "MAX_PENDING", 10)

    async def hang(self, addr) -> None:  # every dial stays pending, so the bound is what limits them
        await asyncio.sleep(30)

    monkeypatch.setattr(Session, "_peer_task", hang)
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=None)
    await swarm.seeder.stop()
    junk = [("127.0.0.1", 30000 + i) for i in range(60)]
    net.append(await FakeNode(os.urandom(20), peers=junk).start())
    storage = Storage(swarm.torrent, tmp_path / "dl")
    dht = DhtClient([net[0].addr], timeout=0.3)
    s = Session(swarm.torrent, storage, set(), NullReporter(), b"-AD0001-abcdefghijkl", dht=dht)
    task = asyncio.create_task(s.run())
    peak = 0
    for _ in range(30):
        await asyncio.sleep(0.02)
        peak = max(peak, len(s._by_addr))
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)
    storage.close()
    dht.close()
    assert peak == 10


async def test_the_waiting_notice_mentions_the_dht(tmp_path: Path, net, monkeypatch) -> None:
    monkeypatch.setattr(session_mod, "WAIT_NOTICE_EVERY", 0.15)
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=None)
    await swarm.seeder.stop()
    net.append(await FakeNode(os.urandom(20), mode="silent").start())
    rec = Recorder()
    storage = Storage(swarm.torrent, tmp_path / "dl")
    dht = DhtClient([net[0].addr], timeout=0.1)
    s = Session(swarm.torrent, storage, set(), rec, b"-AD0001-abcdefghijkl", dht=dht)
    task = asyncio.create_task(s.run())
    await asyncio.sleep(0.6)
    start = time.monotonic()
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)
    assert time.monotonic() - start < 1.0
    storage.close()
    dht.close()
    assert any(n.startswith("waiting for peers (0 trackers, DHT, 0 peers tried)") for n in rec.notices)


def test_waiting_message_format() -> None:
    assert waiting_message(2, False, 5) == "waiting for peers (2 trackers, 5 peers tried)"
    assert waiting_message(0, True, 0, "metadata") == "waiting for peers for metadata (0 trackers, DHT, 0 peers tried)"
