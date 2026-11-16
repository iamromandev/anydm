import asyncio
import contextlib
import errno
import http.client
import os
import threading
import time
import tracemalloc
from pathlib import Path

import pytest
from src.core.errors import DownloadError
from src.core.progress import NullReporter
from src.engine.torrent import peer
from src.engine.torrent import session as session_mod
from src.engine.torrent.metainfo import parse
from src.engine.torrent.peer import Msg
from src.engine.torrent.session import Session, announce_delay
from src.engine.torrent.storage import Storage
from src.engine.torrent.tracker import TrackerError

from tests.engine.torrent.helpers import make_torrent
from tests.engine.torrent.seeding import make_seeder
from tests.engine.torrent.stubs import StubPeer, serve_everything, unchoke_and_ignore
from tests.engine.torrent.trackers import HttpTracker, RawTcpServer

FILES = {"a.bin": os.urandom(70000), "d/b.bin": os.urandom(200000), "c.bin": os.urandom(5)}


class Recorder(NullReporter):
    def __init__(self) -> None:
        self.updates = []
        self.notices: list[str] = []

    def update(self, p) -> None:
        self.updates.append(p)

    def notice(self, msg: str) -> None:
        self.notices.append(msg)


async def fetch(swarm, dest: Path, peers, have=None, report=None) -> Session:
    storage = Storage(swarm.torrent, dest)
    s = Session(swarm.torrent, storage, have or set(), report or NullReporter(), b"-AD0001-abcdefghijkl", extra_peers=peers)
    s.torrent.tiers.clear()  # no tracker: the peers are given
    try:
        await s.run()
    finally:
        storage.close()
    return s


def same(dest: Path) -> bool:
    return all((dest / "pack" / rel).read_bytes() == data for rel, data in FILES.items())


async def test_downloads_all_pieces_from_one_seeder(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    try:
        s = await fetch(swarm, tmp_path / "dl", [("127.0.0.1", swarm.seeder.port)])
        assert s.pieces_downloaded == swarm.torrent.piece_count
        assert same(tmp_path / "dl")
    finally:
        await swarm.seeder.stop()


async def test_downloads_from_two_seeders_and_both_serve(tmp_path: Path) -> None:
    a = await make_seeder(tmp_path / "s1", FILES, delay=0.002)
    b = await make_seeder(tmp_path / "s2", FILES, delay=0.002)
    try:
        await fetch(a, tmp_path / "dl", [("127.0.0.1", a.seeder.port), ("127.0.0.1", b.seeder.port)])
        assert same(tmp_path / "dl")
        assert a.seeder.blocks_served > 0 and b.seeder.blocks_served > 0
    finally:
        await a.seeder.stop()
        await b.seeder.stop()


async def test_peer_sending_corrupt_pieces_is_dropped_and_another_peer_finishes(tmp_path: Path) -> None:
    bad = await make_seeder(tmp_path / "bad", FILES, corrupt={0, 3, 5, 9})
    good = await make_seeder(tmp_path / "good", FILES, delay=0.001)
    try:
        s = await fetch(good, tmp_path / "dl", [("127.0.0.1", bad.seeder.port), ("127.0.0.1", good.seeder.port)])
        assert same(tmp_path / "dl")
        assert s.pieces_downloaded == good.torrent.piece_count
    finally:
        await bad.seeder.stop()
        await good.seeder.stop()


async def test_only_missing_pieces_are_downloaded(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    try:
        dest = tmp_path / "dl"
        first = Storage(swarm.torrent, dest)
        for i in range(0, swarm.torrent.piece_count, 2):
            first.write_piece(i, swarm.storage.read(i, 0, swarm.torrent.piece_size(i)))
        have = first.recheck()
        first.close()
        s = await fetch(swarm, dest, [("127.0.0.1", swarm.seeder.port)], have=have)
        assert s.pieces_downloaded == swarm.torrent.piece_count - len(have)
        assert swarm.seeder.requested_pieces.isdisjoint(have)
        assert same(dest)
    finally:
        await swarm.seeder.stop()


async def test_complete_data_needs_no_peers(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    await swarm.seeder.stop()
    s = await fetch(swarm, tmp_path / "seed", [], have=set(range(swarm.torrent.piece_count)))
    assert s.pieces_downloaded == 0


async def test_progress_reports_phase_peers_and_final_total(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, delay=0.001)
    rec = Recorder()
    try:
        await fetch(swarm, tmp_path / "dl", [("127.0.0.1", swarm.seeder.port)], report=rec)
    finally:
        await swarm.seeder.stop()
    last = rec.updates[-1]
    assert last.done == last.total == swarm.torrent.total
    assert last.phase == "downloading" and last.name == "pack"
    assert any(u.peers == 1 for u in rec.updates) or last.peers is not None


# --- hostile and broken peers ---


def make_session(swarm, dest: Path, peers, report=None, have=None, clear_tiers: bool = True) -> tuple[Session, Storage]:
    storage = Storage(swarm.torrent, dest)
    s = Session(swarm.torrent, storage, have or set(), report or NullReporter(), b"-AD0001-abcdefghijkl", extra_peers=peers)
    if clear_tiers:
        s.torrent.tiers.clear()
    return s, storage


async def finish(s: Session, storage: Storage, timeout: float = 20.0) -> None:
    try:
        await asyncio.wait_for(s.run(), timeout)
    finally:
        storage.close()


async def stop(task: asyncio.Task) -> None:
    task.cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await task


@pytest.fixture
async def stubs():
    made: list[StubPeer] = []

    async def make(swarm, script, **kw) -> StubPeer:
        stub = await StubPeer(swarm.torrent, script, storage=swarm.storage, **kw).start()
        made.append(stub)
        return stub

    yield make
    for stub in made:
        await stub.stop()


async def test_choke_and_unchoke_in_the_middle_of_a_piece_still_completes_the_download(tmp_path: Path, stubs) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)

    async def script(stub, reader, writer) -> None:
        served = 0
        while True:
            m = await stub.recv(reader)
            if m.id == peer.INTERESTED:
                stub.send(writer, Msg(peer.UNCHOKE))
            elif m.id == peer.REQUEST:
                if served == 2:
                    stub.send(writer, Msg(peer.CHOKE))  # mid-piece: the rest of our pipeline is void
                    await writer.drain()
                    await asyncio.sleep(0.05)
                    stub.send(writer, Msg(peer.UNCHOKE))
                served += 1
                if served != 3:
                    stub.send(writer, stub.block(stub.requests[-1]))
            await writer.drain()

    stub = await stubs(swarm, script)
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [stub.addr])
        await finish(s, storage)
        assert same(tmp_path / "dl")
        assert stub.connections == 1 and not s._banned  # nobody was punished for a choke
    finally:
        await swarm.seeder.stop()


async def test_unsolicited_pieces_are_ignored(tmp_path: Path, stubs) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)

    async def script(stub, reader, writer) -> None:
        stub.send(writer, peer.piece(9999, 0, b"x" * 10))  # no such piece
        stub.send(writer, peer.piece(0, 3, b"y" * 100))  # a begin we never asked for
        stub.send(writer, peer.piece(1, 0, b"z" * 16384))  # a piece we have not requested yet
        await serve_everything(stub, reader, writer)

    stub = await stubs(swarm, script)
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [stub.addr])
        await finish(s, storage)
        assert same(tmp_path / "dl") and not s._banned
    finally:
        await swarm.seeder.stop()


def bad_peer_scripts() -> dict:
    async def short_block(stub, reader, writer) -> None:
        while True:
            m = await stub.recv(reader)
            if m.id == peer.INTERESTED:
                stub.send(writer, Msg(peer.UNCHOKE))
            elif m.id == peer.REQUEST:
                index, begin, length = stub.requests[-1]
                stub.send(writer, peer.piece(index, begin, stub.storage.read(index, begin, length)[:-1]))
            await writer.drain()

    async def have_out_of_range(stub, reader, writer) -> None:
        stub.send(writer, peer.have(10_000))
        await writer.drain()
        while True:
            await stub.recv(reader)  # until we hang up

    async def second_bitfield(stub, reader, writer) -> None:
        stub.send_bitfield(writer)  # the handshake step sent the first one
        await writer.drain()
        while True:
            await stub.recv(reader)

    async def bitfield_after_have(stub, reader, writer) -> None:
        stub.send(writer, peer.have(0))
        stub.send_bitfield(writer)
        await writer.drain()
        while True:
            await stub.recv(reader)

    return {
        "wrong-length-block": short_block,
        "have-out-of-range": have_out_of_range,
        "second-bitfield": second_bitfield,
        "bitfield-after-have": bitfield_after_have,
    }


async def run_until_peer_is_dropped(swarm, tmp_path: Path, stub: StubPeer) -> None:
    """The good seeder is slow (about 0.85 s for everything), so a peer that is cut off promptly is told
    apart from one that is merely closed when the whole session ends."""
    s, storage = make_session(swarm, tmp_path / "dl", [stub.addr, ("127.0.0.1", swarm.seeder.port)])
    run = asyncio.create_task(s.run())
    start = asyncio.get_running_loop().time()
    try:
        await asyncio.wait_for(stub.closed.wait(), 5)
        assert stub.closed_at is not None and stub.closed_at - start < 0.5
        await asyncio.wait_for(run, 20)
        assert same(tmp_path / "dl")
    finally:
        await stop(run)
        storage.close()


@pytest.mark.parametrize("name", ["wrong-length-block", "have-out-of-range", "second-bitfield", "bitfield-after-have"])
async def test_a_peer_that_breaks_the_protocol_is_dropped_and_another_peer_finishes(tmp_path: Path, stubs, name: str) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, delay=0.05)
    stub = await stubs(swarm, bad_peer_scripts()[name], initial_bitfield=name != "bitfield-after-have")
    try:
        await run_until_peer_is_dropped(swarm, tmp_path, stub)
    finally:
        await swarm.seeder.stop()


async def test_a_wrong_length_bitfield_drops_the_peer(tmp_path: Path, stubs) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, delay=0.05)

    async def long_bitfield(stub, reader, writer) -> None:
        stub.send(writer, Msg(peer.BITFIELD, bytes(40)))
        await writer.drain()
        while True:
            await stub.recv(reader)

    stub = await stubs(swarm, long_bitfield, initial_bitfield=False)
    try:
        await run_until_peer_is_dropped(swarm, tmp_path, stub)
    finally:
        await swarm.seeder.stop()


async def test_a_peer_that_ignores_requests_is_dropped_despite_keep_alives(tmp_path: Path, stubs, monkeypatch) -> None:
    monkeypatch.setattr(session_mod, "REQUEST_TIMEOUT", 0.4)
    files = {"big.bin": os.urandom(1_500_000)}
    swarm = await make_seeder(tmp_path / "seed", files, delay=0.03)
    stub = await stubs(swarm, unchoke_and_ignore)
    s, storage = make_session(swarm, tmp_path / "dl", [stub.addr])
    run = asyncio.create_task(s.run())
    try:
        while stub.first_request_at is None:
            await asyncio.sleep(0.01)
        s._add_peer(("127.0.0.1", swarm.seeder.port))  # a good peer shows up later
        await asyncio.wait_for(run, 30)
        assert stub.closed_at is not None
        assert stub.closed_at - stub.first_request_at < 1.2  # 0.4 s timeout, not "whenever the keep-alives stop"
        assert (tmp_path / "dl" / "pack" / "big.bin").read_bytes() == files["big.bin"]
    finally:
        await stop(run)
        storage.close()
        await swarm.seeder.stop()


@pytest.mark.parametrize("kind", ["silent", "keep-alives", "haves"])
async def test_a_peer_that_delivers_nothing_is_dropped_after_the_idle_limit(tmp_path: Path, stubs, monkeypatch, kind: str) -> None:
    monkeypatch.setattr(session_mod, "IDLE_LIMIT", 0.3)
    swarm = await make_seeder(tmp_path / "seed", FILES)

    async def script(stub, reader, writer) -> None:
        async def chatter() -> None:
            while True:
                stub.send(writer, Msg(None) if kind == "keep-alives" else peer.have(0))
                await writer.drain()
                await asyncio.sleep(0.03)

        task = asyncio.create_task(chatter()) if kind != "silent" else None
        try:
            while True:
                await stub.recv(reader)
        finally:
            if task:
                task.cancel()

    stub = await stubs(swarm, script, has=set())
    s, storage = make_session(swarm, tmp_path / "dl", [stub.addr])
    run = asyncio.create_task(s.run())
    try:
        start = asyncio.get_running_loop().time()
        await asyncio.wait_for(stub.closed.wait(), 5)
        assert stub.closed_at is not None and stub.closed_at - start < 2.5
    finally:
        await stop(run)
        storage.close()
        await swarm.seeder.stop()


async def test_a_peer_that_never_answers_the_handshake_is_dropped_at_the_connect_timeout(tmp_path: Path, stubs) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    stub = await stubs(swarm, serve_everything, handshake=False)
    storage = Storage(swarm.torrent, tmp_path / "dl")
    s = Session(swarm.torrent, storage, set(), NullReporter(), b"-AD0001-abcdefghijkl", extra_peers=[stub.addr], connect_timeout=0.2)
    s.torrent.tiers.clear()
    run = asyncio.create_task(s.run())
    try:
        await asyncio.wait_for(stub.closed.wait(), 3)
        assert s._active == 0
    finally:
        await stop(run)
        storage.close()
        await swarm.seeder.stop()


async def test_a_peer_that_sends_a_corrupt_piece_is_banned_by_address_and_cut_off(tmp_path: Path) -> None:
    bad = await make_seeder(tmp_path / "bad", FILES, corrupt=set(range(20)))
    good = await make_seeder(tmp_path / "good", FILES, delay=0.001)
    bad_addr, good_addr = ("127.0.0.1", bad.seeder.port), ("127.0.0.1", good.seeder.port)
    try:
        s, storage = make_session(good, tmp_path / "dl", [bad_addr, good_addr])
        await finish(s, storage)
        assert same(tmp_path / "dl")
        assert s._banned == {bad_addr}  # the neighbour on the same IP, another port, is not punished
        assert not s._add_peer(bad_addr)
        assert bad.seeder.connections == 1
    finally:
        await bad.seeder.stop()
        await good.seeder.stop()


async def test_banning_an_address_cancels_every_other_connection_to_it(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    await swarm.seeder.stop()
    s, storage = make_session(swarm, tmp_path / "dl", [])
    bad, other = ("203.0.113.1", 5), ("203.0.113.2", 5)
    mine = [asyncio.create_task(asyncio.sleep(60)) for _ in range(2)]
    theirs = asyncio.create_task(asyncio.sleep(60))
    s._by_addr[bad], s._by_addr[other] = set(mine), {theirs}
    s._ban(bad)
    await asyncio.gather(*mine, return_exceptions=True)
    assert all(t.cancelled() for t in mine) and not theirs.cancelled()
    assert not s._add_peer(bad)
    theirs.cancel()
    storage.close()


async def test_a_flood_of_peer_candidates_is_capped(tmp_path: Path, monkeypatch) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    await swarm.seeder.stop()

    async def hang(self, addr) -> None:
        await asyncio.sleep(60)

    monkeypatch.setattr(Session, "_peer_task", hang)
    s, storage = make_session(swarm, tmp_path / "dl", [])
    try:
        s._add_peers([(f"10.{i // 60000}.{i // 250 % 240}.{i % 250 + 1}", 6881) for i in range(5000)])
        assert len(s._by_addr) == session_mod.MAX_NEW_PEERS
        s._add_peers([(f"11.0.{i // 250}.{i % 250 + 1}", 6881) for i in range(5000)])
        assert len(s._by_addr) <= session_mod.MAX_PENDING  # the pending set is bounded too
        assert not s._add_peer(("12.0.0.1", 0)) and not s._add_peer(("12.0.0.1", 70000))
    finally:
        for t in list(s._tasks):
            t.cancel()
        await asyncio.gather(*s._tasks, return_exceptions=True)
        storage.close()


@pytest.mark.parametrize("error", [ValueError("bad host"), UnicodeError("bad idna")])
async def test_a_peer_address_that_makes_the_dial_raise_is_just_a_failed_peer(tmp_path: Path, monkeypatch, error) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    await swarm.seeder.stop()

    async def boom(*a, **kw):
        raise error

    monkeypatch.setattr(asyncio, "open_connection", boom)
    s, storage = make_session(swarm, tmp_path / "dl", [])
    await s._peer_task(("1.2.3.4", 5))  # must not raise
    storage.close()


def test_the_announce_delay_honours_the_tracker_within_sane_bounds() -> None:
    assert announce_delay(1800) == 1800
    assert announce_delay(5) == session_mod.MIN_ANNOUNCE
    assert announce_delay(5, 300) == 300
    assert announce_delay(900, 1200) == 1200
    assert announce_delay(10**12) == session_mod.MAX_ANNOUNCE


async def announces_in(tmp_path: Path, seconds: float, monkeypatch, interval: int, **patches) -> int:
    for k, v in patches.items():
        monkeypatch.setattr(session_mod, k, v)
    tracker = HttpTracker([], interval=interval).start()
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=tracker.url)
    await swarm.seeder.stop()
    s, storage = make_session(swarm, tmp_path / "dl", [], clear_tiers=False)
    run = asyncio.create_task(s.run())
    try:
        await asyncio.sleep(seconds)
        return len(tracker.queries)
    finally:
        await stop(run)
        storage.close()
        tracker.stop()


async def test_with_too_few_peers_it_announces_again_early_but_rate_limited(tmp_path: Path, monkeypatch) -> None:
    n = await announces_in(tmp_path, 1.1, monkeypatch, 1800, MIN_ANNOUNCE=0.3, ANNOUNCE_POLL=0.02)
    assert 3 <= n <= 5


async def test_the_tracker_interval_is_honoured_when_peers_are_enough(tmp_path: Path, monkeypatch) -> None:
    n = await announces_in(tmp_path, 0.7, monkeypatch, 1800, MIN_ANNOUNCE=0.1, ANNOUNCE_POLL=0.02, LOW_PEERS=0)
    assert n == 1


async def test_a_hostile_tiny_interval_is_raised_to_the_minimum(tmp_path: Path, monkeypatch) -> None:
    n = await announces_in(tmp_path, 1.1, monkeypatch, 1, MIN_ANNOUNCE=1.5, ANNOUNCE_POLL=0.02, LOW_PEERS=0)
    assert n == 1  # the tracker's 1 s would have brought a second one at 1.0 s


async def test_a_peer_with_nothing_we_need_is_never_told_we_are_interested(tmp_path: Path, stubs) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, delay=0.001)
    useless = await stubs(swarm, serve_everything, has=set())
    useful = await stubs(swarm, serve_everything)
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [useless.addr, useful.addr, ("127.0.0.1", swarm.seeder.port)])
        await finish(s, storage)
        await asyncio.wait_for(useless.closed.wait(), 3)
        assert peer.INTERESTED not in [m.id for m in useless.messages]
        assert peer.INTERESTED in [m.id for m in useful.messages]
    finally:
        await swarm.seeder.stop()


async def test_interest_is_withdrawn_when_the_peer_no_longer_has_anything_we_need(tmp_path: Path, stubs) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, delay=0.001)

    async def never_unchokes(stub, reader, writer) -> None:
        while True:
            await stub.recv(reader)

    stub = await stubs(swarm, never_unchokes, has={0})
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [stub.addr, ("127.0.0.1", swarm.seeder.port)])
        await finish(s, storage)
        await asyncio.wait_for(stub.closed.wait(), 3)
        ids = [m.id for m in stub.messages]
        assert ids == [peer.INTERESTED, peer.NOT_INTERESTED]
    finally:
        await swarm.seeder.stop()


async def test_pieces_of_a_megabyte_or_more_are_hashed_off_the_event_loop(tmp_path: Path, monkeypatch) -> None:
    seen: list[int] = []
    real = session_mod._sha_ok

    def spy(blocks, expected) -> bool:
        seen.append(threading.get_ident())
        return real(blocks, expected)

    monkeypatch.setattr(session_mod, "_sha_ok", spy)
    swarm = await make_seeder(tmp_path / "seed", {"a.bin": os.urandom(2_500_000)}, piece_len=1 << 20)
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [("127.0.0.1", swarm.seeder.port)])
        await finish(s, storage)
        assert len(seen) == 3 and seen.count(threading.get_ident()) == 1  # only the 0.4 MB last piece is inline
    finally:
        await swarm.seeder.stop()


async def test_small_pieces_are_hashed_inline(tmp_path: Path, monkeypatch) -> None:
    seen: list[int] = []
    real = session_mod._sha_ok
    monkeypatch.setattr(session_mod, "_sha_ok", lambda b, e: (seen.append(threading.get_ident()), real(b, e))[1])
    swarm = await make_seeder(tmp_path / "seed", FILES)
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [("127.0.0.1", swarm.seeder.port)])
        await finish(s, storage)
        assert seen and set(seen) == {threading.get_ident()}
    finally:
        await swarm.seeder.stop()


async def test_a_huge_piece_is_not_allocated_before_its_data_arrives(tmp_path: Path, stubs) -> None:
    raw, _ = make_torrent({"a.bin": bytes(33 << 20)}, piece_len=32 << 20)
    t = parse(raw)
    storage = Storage(t, tmp_path / "dl")

    async def sit(stub, reader, writer) -> None:
        while True:
            m = await stub.recv(reader)
            if m.id == peer.INTERESTED:
                stub.send(writer, Msg(peer.UNCHOKE))
                await writer.drain()

    stub = await StubPeer(t, sit).start()
    s = Session(t, storage, set(), NullReporter(), b"-AD0001-abcdefghijkl", extra_peers=[stub.addr])
    s.torrent.tiers.clear()
    tracemalloc.start()
    run = asyncio.create_task(s.run())
    try:
        while stub.first_request_at is None:
            await asyncio.sleep(0.01)
        await asyncio.sleep(0.2)
        _, peak = tracemalloc.get_traced_memory()
        assert peak < 4 << 20, peak  # a 32 MiB buffer per peer would show here
    finally:
        tracemalloc.stop()
        await stop(run)
        storage.close()
        await stub.stop()


async def test_a_full_disk_ends_the_download_with_a_clear_error(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [("127.0.0.1", swarm.seeder.port)])

        def full(index: int, data: bytes) -> None:
            raise OSError(errno.ENOSPC, "No space left on device")

        storage.write_piece = full  # ty: ignore[invalid-assignment]
        with pytest.raises(DownloadError, match="cannot write to disk"):
            await finish(s, storage)
    finally:
        await swarm.seeder.stop()


async def test_a_failing_resume_save_does_not_mask_a_cancellation(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", {"big.bin": os.urandom(1_000_000)}, delay=0.01)
    rec = Recorder()
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [("127.0.0.1", swarm.seeder.port)], report=rec)

        def broken(have) -> None:
            raise OSError(errno.EIO, "Input/output error")

        storage.save = broken  # ty: ignore[invalid-assignment]
        run = asyncio.create_task(s.run())
        await asyncio.sleep(0.3)
        run.cancel()
        with pytest.raises(asyncio.CancelledError):
            await run
        assert any("resume record" in n for n in rec.notices)
        storage.close()
    finally:
        await swarm.seeder.stop()


async def test_the_final_save_waits_for_a_periodic_save_still_running(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(session_mod, "SAVE_EVERY", 0.05)
    swarm = await make_seeder(tmp_path / "seed", {"big.bin": os.urandom(1_000_000)}, delay=0.01)
    running, peak, calls = 0, 0, 0
    lock = threading.Lock()
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [("127.0.0.1", swarm.seeder.port)])

        def slow_save(have) -> None:
            nonlocal running, peak, calls
            with lock:
                running += 1
                calls += 1
                peak = max(peak, running)
            time.sleep(0.3)
            with lock:
                running -= 1

        storage.save = slow_save  # ty: ignore[invalid-assignment]
        run = asyncio.create_task(s.run())
        await asyncio.sleep(0.2)  # inside the first periodic save
        run.cancel()
        with pytest.raises(asyncio.CancelledError):
            await run
        assert calls >= 2 and peak == 1
        storage.close()
    finally:
        await swarm.seeder.stop()


# --- trackers that misbehave ---


async def test_garbage_trackers_in_earlier_tiers_do_not_stop_the_download_or_leak_the_passkey(tmp_path: Path) -> None:
    good = HttpTracker([]).start()
    garbage = RawTcpServer(b"garbage\r\n\r\n").start()
    swarm = await make_seeder(tmp_path / "seed", FILES)
    good.peers = [("127.0.0.1", swarm.seeder.port)]
    swarm.torrent.tiers[:] = [
        [garbage.url],
        ["udp://127.0.0.1:99999/announce?passkey=SECRET"],
        ["http://[bad/announce?passkey=SECRET"],
        [good.url],
    ]
    rec = Recorder()
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [], report=rec, clear_tiers=False)
        await finish(s, storage)
        assert same(tmp_path / "dl")
        assert "SECRET" not in "".join(rec.notices)
        assert [q.get("event") for q in good.queries] == [["started"], ["completed"], ["stopped"]]
    finally:
        await swarm.seeder.stop()
        good.stop()
        garbage.stop()


async def test_a_tracker_error_of_any_type_does_not_end_the_tracker_loop(tmp_path: Path, monkeypatch) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    swarm.torrent.tiers[:] = [["http://127.0.0.1:1/announce"]]
    calls = 0
    real = session_mod.announce_tiers

    async def flaky(tiers, params, event=None, **kw):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise http.client.BadStatusLine("garbage")
        if calls == 2:
            raise TrackerError("down")
        from src.engine.torrent.tracker import AnnounceResult

        if event == "started" or event is None:
            return AnnounceResult(1800, [("127.0.0.1", swarm.seeder.port)])
        return await real(tiers, params, event, **kw)

    monkeypatch.setattr(session_mod, "announce_tiers", flaky)
    monkeypatch.setattr(session_mod, "TRACKER_RETRY", 0.05)
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [], clear_tiers=False)
        await finish(s, storage)
        assert same(tmp_path / "dl") and calls >= 3
    finally:
        await swarm.seeder.stop()


async def test_a_failing_completed_announce_is_not_an_error(tmp_path: Path, monkeypatch) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    swarm.torrent.tiers[:] = [["http://127.0.0.1:1/announce"]]

    async def once(tiers, params, event=None, **kw):
        if event == "started":
            from src.engine.torrent.tracker import AnnounceResult

            return AnnounceResult(1800, [("127.0.0.1", swarm.seeder.port)])
        raise http.client.BadStatusLine("garbage")  # `completed` and `stopped` blow up with a stray type

    monkeypatch.setattr(session_mod, "announce_tiers", once)
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [], clear_tiers=False)
        await finish(s, storage)  # no traceback
        assert same(tmp_path / "dl")
    finally:
        await swarm.seeder.stop()


async def test_with_every_tracker_dead_it_waits_with_a_notice_every_interval_and_stops_on_cancel(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(session_mod, "WAIT_NOTICE_EVERY", 0.15)
    monkeypatch.setattr(session_mod, "TRACKER_RETRY", 0.1)
    swarm = await make_seeder(tmp_path / "seed", FILES)
    await swarm.seeder.stop()
    swarm.torrent.tiers[:] = [["http://127.0.0.1:1/announce?passkey=SECRET"], ["udp://127.0.0.1:99999/announce?passkey=SECRET"]]
    rec = Recorder()
    s, storage = make_session(swarm, tmp_path / "dl", [], report=rec, clear_tiers=False)
    run = asyncio.create_task(s.run())
    await asyncio.sleep(0.8)
    assert not run.done()  # it keeps waiting rather than failing
    waiting = [n for n in rec.notices if n.startswith("waiting for peers (2 trackers, 0 peers tried)")]
    assert len(waiting) >= 3
    assert any(n.startswith("tracker:") for n in rec.notices) and "SECRET" not in "".join(rec.notices)
    start = time.monotonic()
    run.cancel()
    with pytest.raises(asyncio.CancelledError):
        await run
    assert time.monotonic() - start < 1.0
    storage.close()


async def test_a_torrent_with_pad_files_downloads_without_creating_them(tmp_path: Path) -> None:
    files = {"a": os.urandom(20000), ".pad/12768": bytes(12768), "b": os.urandom(16384)}
    swarm = await make_seeder(tmp_path / "seed", files, attrs={".pad/12768": b"p"})
    try:
        s, storage = make_session(swarm, tmp_path / "dl", [("127.0.0.1", swarm.seeder.port)])
        await finish(s, storage)
        assert (tmp_path / "dl" / "pack" / "a").read_bytes() == files["a"]
        assert (tmp_path / "dl" / "pack" / "b").read_bytes() == files["b"]
        assert sorted(p.name for p in (tmp_path / "dl" / "pack").iterdir()) == ["a", "b"]
    finally:
        await swarm.seeder.stop()
