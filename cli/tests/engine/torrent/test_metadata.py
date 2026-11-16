import asyncio
import contextlib
import os
import time

import pytest
from src.core.progress import NullReporter
from src.engine.torrent import metadata as md
from src.engine.torrent.metadata import MetadataFetcher
from src.engine.torrent.metainfo import parse

from tests.engine.torrent.helpers import Recorder, info_bytes, make_torrent
from tests.engine.torrent.stubs import StubPeer, serve_metadata

# 2000 tiny pieces: a 40 KB info dictionary, so three 16 KiB metadata pieces
RAW, _ = make_torrent({"a.bin": os.urandom(32000)}, piece_len=16)
INFO = info_bytes(RAW)
TORRENT = parse(RAW)
PEER_ID = b"-AD0001-abcdefghijkl"


@contextlib.asynccontextmanager
async def peers(*scripts, info=INFO):
    stubs = [await StubPeer(TORRENT, s, has=set(), initial_bitfield=False).start() for s in scripts]
    try:
        yield stubs
    finally:
        for s in stubs:
            await s.stop()


def fetcher(stubs, report=None, **kw) -> MetadataFetcher:
    kw.setdefault("connect_timeout", 2.0)
    return MetadataFetcher(TORRENT.infohash, PEER_ID, report or NullReporter(), extra_peers=[s.addr for s in stubs], **kw)


def test_the_info_dictionary_spans_several_metadata_pieces() -> None:
    assert 2 * md.PIECE < len(INFO) < 3 * md.PIECE


async def test_fetches_and_verifies_the_metadata_from_one_peer() -> None:
    async with peers(serve_metadata(INFO)) as stubs:
        got = await asyncio.wait_for(fetcher(stubs).run(), 10)
    assert got == INFO
    assert sorted(stubs[0].served) == [0, 1, 2]


async def test_pieces_are_requested_from_several_peers() -> None:
    async with peers(serve_metadata(INFO, delay=0.05), serve_metadata(INFO, delay=0.05), serve_metadata(INFO, delay=0.05)) as stubs:
        got = await asyncio.wait_for(fetcher(stubs).run(), 10)
        assert got == INFO
        assert sum(1 for s in stubs if s.served) >= 2
        assert sorted(i for s in stubs for i in s.served) == [0, 1, 2]


async def test_a_peer_serving_corrupt_pieces_is_banned_and_another_peer_finishes() -> None:
    async with peers(serve_metadata(INFO, corrupt={0, 1, 2})) as bad, peers(serve_metadata(INFO)) as good:
        f = fetcher(bad)  # it is the only source at first, so the blame is clear
        task = asyncio.create_task(f.run())
        deadline = time.monotonic() + 5
        while bad[0].addr not in f.banned and time.monotonic() < deadline:
            await asyncio.sleep(0.02)
        assert bad[0].addr in f.banned and not task.done()
        f.add_peers([good[0].addr])
        assert await asyncio.wait_for(task, 10) == INFO
        assert good[0].addr not in f.banned
        assert bad[0].connections == 1  # a banned peer is not dialled again


async def test_corrupt_pieces_mixed_with_good_ones_still_end_with_the_right_metadata_and_no_honest_peer_banned() -> None:
    async with peers(serve_metadata(INFO, corrupt={1}, delay=0.02), serve_metadata(INFO, delay=0.02)) as stubs:
        f = fetcher(stubs)
        assert await asyncio.wait_for(f.run(), 10) == INFO
        assert stubs[1].addr not in f.banned  # the mixed failure blames nobody; one peer at a time sorts it out


async def test_a_peer_claiming_oversized_metadata_is_banned_without_a_request() -> None:
    async with peers(serve_metadata(INFO, claim_size=md.MAX_METADATA + 1), serve_metadata(INFO)) as stubs:
        f = fetcher(stubs)
        got = await asyncio.wait_for(f.run(), 10)
        assert got == INFO
        assert stubs[0].addr in f.banned
        assert stubs[0].served == [] and not any(m.id == 20 and m.payload[0] != 0 for m in stubs[0].messages)


async def test_a_peer_claiming_a_wrong_size_cannot_poison_the_honest_ones() -> None:
    async with peers(serve_metadata(INFO, claim_size=len(INFO) + 5000), serve_metadata(INFO)) as stubs:
        f = fetcher(stubs)
        assert await asyncio.wait_for(f.run(), 10) == INFO


async def test_a_peer_that_rejects_a_piece_does_not_stop_the_download() -> None:
    async with peers(serve_metadata(INFO, reject={1}), serve_metadata(INFO, delay=0.02)) as stubs:
        f = fetcher(stubs)
        got = await asyncio.wait_for(f.run(), 10)
        assert got == INFO
        assert stubs[1].served.count(1) >= 1
        assert not f.banned


async def test_a_peer_that_sends_a_piece_of_the_wrong_length_is_banned() -> None:
    async with peers(serve_metadata(INFO, wrong_length=True), serve_metadata(INFO, delay=0.02)) as stubs:
        f = fetcher(stubs)
        assert await asyncio.wait_for(f.run(), 10) == INFO
        assert stubs[0].addr in f.banned


async def test_a_peer_without_ut_metadata_is_dropped_not_banned() -> None:
    async with peers(serve_metadata(INFO, advertise=False), serve_metadata(INFO)) as stubs:
        f = fetcher(stubs)
        assert await asyncio.wait_for(f.run(), 10) == INFO
        assert not f.banned


async def test_with_only_bad_peers_it_keeps_waiting_and_cancels_within_a_second() -> None:
    async with peers(serve_metadata(INFO, corrupt={0, 1, 2}), serve_metadata(INFO, claim_size=md.MAX_METADATA + 1)) as stubs:
        task = asyncio.create_task(fetcher(stubs).run())
        await asyncio.sleep(0.5)
        assert not task.done()
        start = time.monotonic()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert time.monotonic() - start < 1.0


async def test_progress_reports_the_metadata_phase_with_the_peer_count() -> None:
    rec = Recorder()
    async with peers(serve_metadata(INFO, delay=0.1)) as stubs:
        task = asyncio.create_task(fetcher(stubs, rec, name="My Movie").run())
        await asyncio.sleep(0.6)
        await asyncio.wait_for(task, 10)
    phases = [u for u in rec.updates if u.phase == "metadata"]
    assert phases and all(u.name == "My Movie" for u in phases)
    assert any(u.peers == 1 for u in phases)
    assert phases[-1].total == len(INFO) and phases[-1].done == len(INFO)


async def test_a_message_flood_or_junk_extension_payload_only_costs_that_peer() -> None:
    async def junk(stub, reader, writer):
        stub.send(writer, peer_ext_junk())
        await writer.drain()
        await stub.recv(reader)

    def peer_ext_junk():
        from src.engine.torrent.peer import Msg

        return Msg(20, b"\x00" + b"d" * 100)

    async with peers(junk, serve_metadata(INFO)) as stubs:
        assert await asyncio.wait_for(fetcher(stubs).run(), 10) == INFO



# --- C1: peers claiming made-up sizes must not use up the room for the honest size ---


@pytest.mark.parametrize("liars", [3, 5])
async def test_peers_claiming_distinct_wrong_sizes_cannot_stall_the_metadata(liars: int) -> None:
    scripts = [serve_metadata(INFO, claim_size=len(INFO) + 1000 * (i + 1)) for i in range(liars)]
    async with peers(*scripts) as lying, peers(serve_metadata(INFO)) as good:
        f = fetcher(lying)
        task = asyncio.create_task(f.run())
        await asyncio.sleep(1.0)  # the liars are all in by now
        f.add_peers([good[0].addr])
        assert await asyncio.wait_for(task, 8) == INFO


async def test_liars_that_stay_connected_and_silent_cannot_stall_the_metadata() -> None:
    scripts = [serve_metadata(INFO, claim_size=len(INFO) + 1000 * (i + 1), delay=300) for i in range(4)]
    async with peers(*scripts) as lying, peers(serve_metadata(INFO)) as good:
        f = fetcher(lying)
        task = asyncio.create_task(f.run())
        await asyncio.sleep(1.0)
        assert f._active >= 3  # the liars are still connected, and they hold every size there is room for
        f.add_peers([good[0].addr])
        assert await asyncio.wait_for(task, 8) == INFO


async def test_a_size_nobody_is_connected_for_any_more_gives_its_room_back() -> None:
    async with peers(serve_metadata(INFO, claim_size=len(INFO) + 1, reject={0, 1, 2}, delay=0.0)) as stubs:
        f = fetcher(stubs)
        task = asyncio.create_task(f.run())
        deadline = time.monotonic() + 5
        while not f._bufs and time.monotonic() < deadline:
            await asyncio.sleep(0.02)
        assert f._bufs
        await stubs[0].stop()
        for t in list(f._tasks.values()):
            t.cancel()  # the peer leaves
        await asyncio.sleep(0.3)
        assert not f._bufs
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


# --- S2: the owner of a solo attempt cannot hold the slot ---


def solo_fetcher(stubs, report=None, **kw) -> MetadataFetcher:
    """A fetcher in the state a mixed hash failure leaves it in: the next attempt is made by one peer alone."""
    f = fetcher(stubs, report, **kw)
    buf = md._Buf(len(INFO))
    buf.solo = True
    f._bufs[len(INFO)] = buf
    return f


async def wait_until(cond, seconds: float = 5.0) -> None:
    deadline = time.monotonic() + seconds
    while not cond() and time.monotonic() < deadline:
        await asyncio.sleep(0.02)


async def test_a_solo_owner_that_drips_just_under_the_request_timeout_is_banned_at_the_deadline(monkeypatch) -> None:
    monkeypatch.setattr(md, "REQUEST_TIMEOUT", 1.0)
    monkeypatch.setattr(md, "SOLO_MIN", 1.0)
    monkeypatch.setattr(md, "SOLO_PER_PIECE", 0.0)
    async with peers(serve_metadata(INFO, delay=0.45)) as slow, peers(serve_metadata(INFO)) as good:
        f = solo_fetcher(slow)
        task = asyncio.create_task(f.run())
        await wait_until(lambda: slow[0].addr in f.banned or task.done(), 4)
        assert slow[0].addr in f.banned and not task.done()  # it would have needed 1.35 s: too long for the whole attempt
        f.add_peers([good[0].addr])
        assert await asyncio.wait_for(task, 8) == INFO  # a new owner takes over


async def test_the_solo_deadline_grows_with_the_number_of_pieces() -> None:
    assert md.solo_deadline(3) == max(md.SOLO_MIN, 3 * md.SOLO_PER_PIECE) == 10.0
    assert md.solo_deadline(1024) == 2048.0


async def test_a_solo_owner_that_rejects_a_piece_is_banned_and_the_slot_is_free_again() -> None:
    async with peers(serve_metadata(INFO, reject={1})) as bad, peers(serve_metadata(INFO)) as good:
        f = solo_fetcher(bad)
        task = asyncio.create_task(f.run())
        await wait_until(lambda: bad[0].addr in f.banned, 3)
        assert bad[0].addr in f.banned and not task.done()
        f.add_peers([good[0].addr])
        assert await asyncio.wait_for(task, 8) == INFO


async def test_a_solo_owner_that_hangs_up_is_banned_and_refused_when_it_comes_back() -> None:
    async with peers(serve_metadata(INFO, leave_after_first_request=True)) as stubs:
        f = solo_fetcher(stubs)
        task = asyncio.create_task(f.run())
        await wait_until(lambda: stubs[0].addr in f.banned, 3)
        assert stubs[0].addr in f.banned
        f.add_peers([stubs[0].addr])
        await asyncio.sleep(0.3)
        assert stubs[0].connections == 1  # not dialled again
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


async def test_a_peer_that_is_not_the_solo_owner_is_not_banned_for_leaving() -> None:
    async with peers(serve_metadata(INFO, delay=0.05)) as owner, peers(serve_metadata(INFO, leave_after_first_request=True)) as other:
        f = solo_fetcher(owner)
        task = asyncio.create_task(f.run())
        await wait_until(lambda: owner[0].served, 3)  # the owner is under way
        f.add_peers([other[0].addr])
        assert await asyncio.wait_for(task, 8) == INFO
        assert not f.banned


# --- S3: no waiting out the tracker interval while no peer is useful ---


async def test_the_tracker_is_asked_again_after_min_announce_while_no_peer_is_connected(monkeypatch) -> None:
    from tests.engine.torrent.trackers import HttpTracker

    monkeypatch.setattr(md, "MIN_ANNOUNCE", 0.2)
    monkeypatch.setattr(md, "ANNOUNCE_POLL", 0.05)
    tracker = HttpTracker([], interval=1800).start()
    try:
        f = MetadataFetcher(TORRENT.infohash, PEER_ID, NullReporter(), tiers=[[tracker.url]])
        task = asyncio.create_task(f.run())
        await wait_until(lambda: len(tracker.queries) >= 3, 4)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        assert len(tracker.queries) >= 3
    finally:
        tracker.stop()


async def test_the_tracker_interval_is_kept_while_a_peer_is_connected(monkeypatch) -> None:
    from tests.engine.torrent.trackers import HttpTracker

    monkeypatch.setattr(md, "MIN_ANNOUNCE", 0.2)
    monkeypatch.setattr(md, "ANNOUNCE_POLL", 0.05)
    tracker = HttpTracker([], interval=1800).start()
    try:
        async with peers(serve_metadata(INFO, delay=300)) as stubs:
            f = fetcher(stubs, tiers=[[tracker.url]])
            task = asyncio.create_task(f.run())
            await asyncio.sleep(1.0)
            assert f._active == 1
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        assert len(tracker.queries) == 1
    finally:
        tracker.stop()


async def test_the_peers_that_spoke_the_extension_protocol_are_kept_for_the_session() -> None:
    async with peers(serve_metadata(INFO), serve_metadata(INFO, advertise=False)) as stubs:
        f = fetcher(stubs)
        assert await asyncio.wait_for(f.run(), 10) == INFO
        assert stubs[0].addr in f.peers_seen
        assert stubs[1].addr not in f.peers_seen


# --- S7: REJECTs we did not ask for ---


async def test_an_unasked_reject_does_not_mark_the_piece_as_unavailable_from_that_peer() -> None:
    async with peers(serve_metadata(INFO, junk_rejects=[2])) as stubs:
        f = fetcher(stubs)
        assert await asyncio.wait_for(f.run(), 10) == INFO  # piece 2 still came from the only peer there is


async def test_a_flood_of_unasked_rejects_gets_the_peer_dropped() -> None:
    async with peers(serve_metadata(INFO, junk_rejects=range(1000, 1100))) as flooder, peers(serve_metadata(INFO)) as good:
        f = fetcher(flooder)
        task = asyncio.create_task(f.run())
        await asyncio.wait_for(flooder[0].closed.wait(), 3)  # hung up on, while the fetch is still under way
        assert not task.done() and flooder[0].addr not in f.banned
        f.add_peers([good[0].addr])
        assert await asyncio.wait_for(task, 10) == INFO


# --- N5: requests from the peer get a REJECT ---


async def test_a_metadata_request_from_a_peer_is_answered_with_a_reject() -> None:
    async with peers(serve_metadata(INFO, ask_us=[0, 5])) as stubs:
        f = fetcher(stubs)
        assert await asyncio.wait_for(f.run(), 10) == INFO
    answers = []
    for m in stubs[0].messages:
        if m.id == 20 and m.payload[0] == 7:
            kind, index, _, _ = md.parse_message(m.payload[1:])
            if kind == md.REJECT:  # the fetcher's own requests to the stub are of no interest here
                answers.append((kind, index))
    assert answers == [(md.REJECT, 0), (md.REJECT, 5)]


# --- N6: whatever goes wrong in a peer task is retrieved ---


async def test_an_unexpected_error_in_a_peer_task_drops_the_peer_quietly(monkeypatch) -> None:
    import gc

    problems: list[dict] = []
    asyncio.get_running_loop().set_exception_handler(lambda loop, ctx: problems.append(ctx))
    original = MetadataFetcher._on_message
    seen: list[object] = []

    def explode_once(self, p, m):
        if not seen:
            seen.append(p.addr)
            raise RuntimeError("unexpected")
        return original(self, p, m)

    monkeypatch.setattr(MetadataFetcher, "_on_message", explode_once)
    async with peers(serve_metadata(INFO), serve_metadata(INFO)) as stubs:
        f = fetcher(stubs)
        assert await asyncio.wait_for(f.run(), 10) == INFO
    gc.collect()
    await asyncio.sleep(0)
    assert seen and problems == []


# --- S8: a magnet's tracker pointing at a local service is skipped, with a notice that shows no path ---


async def test_a_magnet_tracker_on_a_local_address_that_is_not_an_announce_url_is_skipped_with_a_clean_notice(monkeypatch) -> None:
    from tests.engine.torrent.trackers import HttpTracker

    monkeypatch.setattr(md, "TRACKER_RETRY", 60.0)
    tracker = HttpTracker([]).start()
    rec = Recorder()
    try:
        f = MetadataFetcher(TORRENT.infohash, PEER_ID, rec, tiers=[[tracker.url.replace("/announce", "/admin?passkey=SECRET")]])
        task = asyncio.create_task(f.run())
        await wait_until(lambda: rec.notices, 3)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        assert tracker.queries == []
        assert any("skipped" in n for n in rec.notices)
        assert "SECRET" not in "".join(rec.notices) and "/admin" not in "".join(rec.notices)
    finally:
        tracker.stop()


async def test_the_name_in_the_progress_is_cleaned_of_controls_and_bidi_overrides() -> None:
    rec = Recorder()
    async with peers(serve_metadata(INFO, delay=0.05)) as stubs:
        await asyncio.wait_for(fetcher(stubs, rec, name="film‮gpj.exe\x9b").run(), 10)
    names = {u.name for u in rec.updates if u.phase == "metadata"}
    assert names == {"filmgpj.exe"}
