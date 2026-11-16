"""The seeder under hostile or merely bad peers: stalls, floods, squatters, and a clean shutdown."""

import asyncio
import contextlib
import threading
import time
from pathlib import Path
from typing import Any

import pytest
from src.engine.torrent import metadata, peer
from src.engine.torrent.bencode import decode
from src.engine.torrent.seed import Seeder
from src.engine.torrent.storage import Storage

from tests.engine.torrent.seeding import (
    Swarm,
    connect,
    make_seeder,
    next_msg,
    send,
    stalled_peer,
    until_hung_up,
    wait_until,
)

FILES = {"a.bin": b"x" * 40000}


async def swarm_with(tmp_path: Path, files: dict[str, bytes] | None = None, **options: Any) -> Swarm:
    return await make_seeder(tmp_path / "seed", files or FILES, options=options)


# --- C1: a peer that stops reading cannot hold the seeder ---


async def test_a_peer_that_stops_reading_loses_its_slot_and_socket_after_the_write_timeout(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path, write_timeout=0.3)
    try:
        _, w = await stalled_peer(swarm)
        await wait_until(lambda: swarm.seeder.peer_count == 1)
        sock = swarm.seeder._remotes[0].writer.get_extra_info("socket")
        await wait_until(lambda: swarm.seeder.peer_count == 0)
        await wait_until(lambda: sock.fileno() == -1)  # the descriptor is released, not left waiting to flush
        w.close()
    finally:
        await swarm.seeder.stop()


@pytest.mark.parametrize("wait", [0.0, 0.6], ids=["stalled just now", "stalled past the write timeout"])
async def test_stop_returns_at_once_while_a_peer_is_not_reading(tmp_path: Path, wait: float) -> None:
    swarm = await swarm_with(tmp_path, write_timeout=0.3 if wait else 30.0)
    _, w = await stalled_peer(swarm)
    await asyncio.sleep(wait)
    started = time.monotonic()
    await asyncio.wait_for(swarm.seeder.stop(), 3)
    assert time.monotonic() - started < 1.0
    assert swarm.seeder.peer_count == 0
    w.close()


async def test_stop_cancels_the_handlers_of_idle_peers_too(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path)
    r, w = await connect(swarm)
    started = time.monotonic()
    await asyncio.wait_for(swarm.seeder.stop(), 3)
    assert time.monotonic() - started < 1.0
    assert await until_hung_up(r) == b""
    w.close()


async def test_a_second_stop_is_harmless(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path)
    await swarm.seeder.stop()
    await asyncio.wait_for(swarm.seeder.stop(), 1)


async def test_stop_before_start_is_harmless(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path)
    await swarm.seeder.stop()
    fresh = Seeder(swarm.torrent, swarm.storage, set(), b"-SD0001-seedseedseed")
    await asyncio.wait_for(fresh.stop(), 1)


# --- C2: choke and unchoke traffic is bounded ---


async def test_a_peer_that_toggles_interest_over_and_over_is_hung_up(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path)
    try:
        _, w = await stalled_peer(swarm, requests=0)
        pair = peer.encode_message(peer.Msg(peer.NOT_INTERESTED)) + peer.encode_message(peer.Msg(peer.INTERESTED))
        for _ in range(500):
            w.write(pair)
        await wait_until(lambda: swarm.seeder.peer_count == 0)
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_the_write_buffer_toward_a_peer_that_never_reads_is_capped(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path, msg_burst=10**9, msg_rate=1e9)  # no rate limit: only the buffer cap is left
    try:
        _, w = await stalled_peer(swarm, requests=0)
        pair = peer.encode_message(peer.Msg(peer.NOT_INTERESTED)) + peer.encode_message(peer.Msg(peer.INTERESTED))
        with contextlib.suppress(ConnectionError):  # the seeder aborts us once its buffer is full
            for i in range(250_000):
                w.write(pair)
                if i % 1000 == 0:
                    await w.drain()
                    await asyncio.sleep(0)
        await wait_until(lambda: swarm.seeder.peer_count == 0)
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_repeating_interested_sends_one_unchoke_only(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path)
    try:
        r, w = await connect(swarm)
        for _ in range(5):
            send(w, peer.Msg(peer.INTERESTED))
        send(w, peer.Msg(peer.NOT_INTERESTED))
        send(w, peer.Msg(peer.NOT_INTERESTED))
        got = [(await next_msg(r)).id for _ in range(2)]
        assert got == [peer.UNCHOKE, peer.CHOKE]
        with pytest.raises(TimeoutError):
            await next_msg(r, 0.3)  # nothing more was sent
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_a_few_changes_of_mind_are_fine(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path)
    try:
        r, w = await connect(swarm)
        for _ in range(3):
            send(w, peer.Msg(peer.INTERESTED))
            assert (await next_msg(r)).id == peer.UNCHOKE
            send(w, peer.Msg(peer.NOT_INTERESTED))
            assert (await next_msg(r)).id == peer.CHOKE
        w.close()
    finally:
        await swarm.seeder.stop()


# --- S1: slots, rotation, ordering, connection limits ---


async def test_an_unchoked_peer_gives_its_slot_to_a_waiting_one_after_the_rotation_time(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path, max_unchoked=1, rotate_every=0.3, idle_unchoke=60.0, choke_interval=0.05)
    try:
        r1, w1 = await connect(swarm, b"a")
        r2, w2 = await connect(swarm, b"b")
        send(w1, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r1)).id == peer.UNCHOKE
        send(w2, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r1)).id == peer.CHOKE
        assert (await next_msg(r2)).id == peer.UNCHOKE
        w1.close()
        w2.close()
    finally:
        await swarm.seeder.stop()


async def test_a_rotated_out_peer_goes_to_the_back_of_the_line(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path, max_unchoked=1, rotate_every=0.3, idle_unchoke=60.0, choke_interval=0.05)
    try:
        r1, w1 = await connect(swarm, b"a")
        r2, w2 = await connect(swarm, b"b")
        send(w1, peer.Msg(peer.INTERESTED))
        send(w2, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r1)).id == peer.UNCHOKE
        assert (await next_msg(r1)).id == peer.CHOKE  # rotated out for b
        assert (await next_msg(r2)).id == peer.UNCHOKE
        assert (await next_msg(r2)).id == peer.CHOKE  # and b for a, which waited
        assert (await next_msg(r1)).id == peer.UNCHOKE
        w1.close()
        w2.close()
    finally:
        await swarm.seeder.stop()


async def test_nobody_is_rotated_out_when_nobody_waits(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path, max_unchoked=1, rotate_every=0.1, idle_unchoke=0.1, choke_interval=0.02)
    try:
        r, w = await connect(swarm)
        send(w, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r)).id == peer.UNCHOKE
        with pytest.raises(TimeoutError):
            await next_msg(r, 0.5)  # many rotation periods pass; no one else wants the slot
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_an_unchoked_peer_that_sends_no_requests_loses_its_slot_when_others_wait(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path, max_unchoked=1, rotate_every=60.0, idle_unchoke=0.3, choke_interval=0.05)
    try:
        r1, w1 = await connect(swarm, b"a")
        r2, w2 = await connect(swarm, b"b")
        send(w1, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r1)).id == peer.UNCHOKE
        send(w2, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r1)).id == peer.CHOKE
        assert (await next_msg(r2)).id == peer.UNCHOKE
        w1.close()
        w2.close()
    finally:
        await swarm.seeder.stop()


async def test_an_unchoked_peer_that_keeps_requesting_keeps_its_slot(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path, max_unchoked=1, rotate_every=60.0, idle_unchoke=0.3, choke_interval=0.05)
    try:
        r1, w1 = await connect(swarm, b"a")
        _, w2 = await connect(swarm, b"b")
        send(w1, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r1)).id == peer.UNCHOKE
        send(w2, peer.Msg(peer.INTERESTED))
        end = time.monotonic() + 0.8  # well past idle_unchoke
        while time.monotonic() < end:
            send(w1, peer.request(0, 0, 100))
            assert (await next_msg(r1)).id == peer.PIECE  # a CHOKE here would fail the test
            await asyncio.sleep(0.05)
        w1.close()
        w2.close()
    finally:
        await swarm.seeder.stop()


async def test_waiting_peers_are_unchoked_in_the_order_they_became_interested(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path, max_unchoked=1)
    try:
        r1, w1 = await connect(swarm, b"a")  # connected first
        r2, w2 = await connect(swarm, b"b")
        r3, w3 = await connect(swarm, b"c")
        send(w1, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r1)).id == peer.UNCHOKE
        send(w3, peer.Msg(peer.INTERESTED))  # c asked before b
        await wait_until(lambda: sum(r.interested for r in swarm.seeder._remotes) == 2)
        send(w2, peer.Msg(peer.INTERESTED))
        await wait_until(lambda: sum(r.interested for r in swarm.seeder._remotes) == 3)
        send(w1, peer.Msg(peer.NOT_INTERESTED))
        assert (await next_msg(r3)).id == peer.UNCHOKE
        with pytest.raises(TimeoutError):
            await next_msg(r2, 0.2)
        for w in (w1, w2, w3):
            w.close()
    finally:
        await swarm.seeder.stop()


async def test_a_connection_that_has_not_handshaken_does_not_use_up_a_peer_slot(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path, max_peers=1)
    ih = swarm.torrent.infohash
    try:
        _, silent = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)  # says nothing
        await wait_until(lambda: swarm.seeder.connections == 1)
        r, w = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
        await asyncio.wait_for(peer.handshake(r, w, ih, b"-AD0001-yyyyyyyyyyyy"), 2)
        assert swarm.seeder.peer_count == 1
        silent.close()
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_no_more_than_three_connections_from_one_address(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path)
    ih = swarm.torrent.infohash
    try:
        held = []
        for i in range(3):
            r, w = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
            await peer.handshake(r, w, ih, b"-AD0001-" + bytes([97 + i]) * 12)
            held.append(w)
        r4, w4 = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
        w4.write(peer.handshake_bytes(ih, b"-AD0001-dddddddddddd"))
        assert await until_hung_up(r4) == b""
        held[0].close()
        await wait_until(lambda: swarm.seeder.peer_count == 2)
        r5, w5 = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
        await asyncio.wait_for(peer.handshake(r5, w5, ih, b"-AD0001-eeeeeeeeeeee"), 2)
        for w in (*held, w4, w5):
            w.close()
    finally:
        await swarm.seeder.stop()


async def test_the_per_address_count_is_released_when_a_connection_never_handshakes(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path, handshake_timeout=0.2)
    ih = swarm.torrent.infohash
    try:
        for _ in range(3):
            r, w = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
            await until_hung_up(r)
            w.close()
        r, w = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
        await asyncio.wait_for(peer.handshake(r, w, ih, b"-AD0001-ffffffffffff"), 2)
        w.close()
    finally:
        await swarm.seeder.stop()


# --- S2: the request queue ---


async def test_the_default_queue_is_250_requests_and_is_advertised_as_reqq(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path)
    try:
        r, w = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
        await peer.handshake(r, w, swarm.torrent.infohash, b"-AD0001-abcdefghijkl")
        m = await next_msg(r)
        while not (m.id == peer.EXTENDED and m.payload[0] == 0):
            m = await next_msg(r)
        d = decode(m.payload[1:])
        assert isinstance(d, dict) and d[b"reqq"] == swarm.seeder.max_queued == 250
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_requests_beyond_the_queue_are_dropped_without_hanging_up(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, delay=0.3, options={"max_queued": 3})
    try:
        r, w = await connect(swarm)
        send(w, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r)).id == peer.UNCHOKE
        for i in range(8):  # three fit the queue (a fourth if the uploader has already taken one); the rest are over it
            send(w, peer.request(0, i * 100, 100))
        begins: list[int] = []
        probed = False
        while 800 not in begins:
            m = await next_msg(r, 3)  # a hang-up would raise here
            assert m.id == peer.PIECE
            begins.append(peer.parse_piece(m.payload)[1])
            if not probed:
                probed = True
                send(w, peer.request(0, 800, 100))  # the queue has room again: still connected, and served
        assert begins[0] == 0 and begins[-1] == 800
        assert 3 <= len(begins) - 1 <= 4  # the rest was dropped, not answered
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_a_peer_that_ignores_the_cap_by_far_is_hung_up(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, delay=0.5, options={"max_queued": 3})
    try:
        r, w = await connect(swarm)
        send(w, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r)).id == peer.UNCHOKE
        for i in range(60):  # 3 over the cap would be forgiven; ten times that is not
            send(w, peer.request(0, i * 10, 10))
        await until_hung_up(r)
        w.close()
    finally:
        await swarm.seeder.stop()


# --- S4: every write is bounded ---


async def test_a_peer_that_stops_reading_metadata_replies_is_dropped_after_the_write_timeout(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", {"a.bin": b"x" * 3000}, piece_len=1, options={"write_timeout": 0.3})
    try:
        _, w = await stalled_peer(swarm, requests=0)
        w.write(peer.encode_message(peer.extension_handshake({metadata.UT_METADATA: 3})))
        for _ in range(300):
            w.write(peer.encode_message(metadata.request_piece(2, 0)))
        await w.drain()
        await wait_until(lambda: swarm.seeder.peer_count == 0)
        w.close()
    finally:
        await swarm.seeder.stop()


# --- S7: the seeder says nothing to a peer that wants another torrent ---


async def test_a_peer_asking_for_another_torrent_gets_no_handshake_back(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path)
    try:
        r, w = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
        w.write(peer.handshake_bytes(b"\x07" * 20, b"-AD0001-abcdefghijkl"))
        assert await until_hung_up(r) == b""
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_a_peer_that_is_not_bittorrent_gets_no_reply(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path)
    try:
        r, w = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
        w.write(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n" + b"\x00" * 80)
        assert await until_hung_up(r) == b""
        w.close()
    finally:
        await swarm.seeder.stop()


# --- S8: reading the disk does not stall the event loop ---


class SlowStorage:
    """Wraps a Storage; each read takes `pause` seconds on whatever thread calls it."""

    def __init__(self, inner: Storage, pause: float) -> None:
        self.inner, self.pause = inner, pause
        self.threads: set[int] = set()

    def read(self, index: int, begin: int, length: int) -> bytes:
        self.threads.add(threading.get_ident())
        time.sleep(self.pause)
        return self.inner.read(index, begin, length)


async def test_a_slow_disk_read_does_not_block_the_event_loop(tmp_path: Path) -> None:
    swarm = await swarm_with(tmp_path)
    slow = SlowStorage(swarm.storage, 0.3)
    swarm.seeder.storage = slow  # ty: ignore[invalid-assignment]
    gaps: list[float] = []

    async def ticker() -> None:
        last = time.monotonic()
        while True:
            await asyncio.sleep(0.01)
            now = time.monotonic()
            gaps.append(now - last)
            last = now

    tick = asyncio.create_task(ticker())
    try:
        r, w = await connect(swarm)
        send(w, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r)).id == peer.UNCHOKE
        send(w, peer.request(0, 0, 100))
        assert (await next_msg(r)).id == peer.PIECE
        assert threading.get_ident() not in slow.threads  # read off the loop's thread
        assert max(gaps) < 0.15
        w.close()
    finally:
        tick.cancel()
        await swarm.seeder.stop()
