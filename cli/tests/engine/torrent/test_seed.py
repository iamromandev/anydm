import asyncio
import gc
from pathlib import Path

import pytest
from src.core.progress import NullReporter
from src.engine.torrent import metadata, peer
from src.engine.torrent.metadata import MetadataFetcher

from tests.engine.torrent.helpers import info_bytes
from tests.engine.torrent.seeding import connect, make_seeder, next_msg, send, until_hung_up, wait_until

FILES = {"a.bin": b"x" * 40000}


async def test_a_connection_that_never_handshakes_is_dropped(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    swarm.seeder.handshake_timeout = 0.2
    try:
        reader, writer = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
        await until_hung_up(reader)  # returns only once the seeder has closed the connection
        writer.close()
    finally:
        await swarm.seeder.stop()


async def test_connections_beyond_the_slot_limit_are_refused_until_one_frees_up(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    swarm.seeder.max_peers = 1
    ih, pid = swarm.torrent.infohash, b"-AD0001-abcdefghijkl"
    try:
        r1, w1 = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
        await peer.handshake(r1, w1, ih, pid)
        r2, w2 = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
        w2.write(peer.handshake_bytes(ih, b"-AD0001-zzzzzzzzzzzz"))
        assert await until_hung_up(r2) == b""  # no slot: hung up without a handshake
        w1.close()
        await wait_until(lambda: swarm.seeder.peer_count == 0)
        r3, w3 = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
        await asyncio.wait_for(peer.handshake(r3, w3, ih, b"-AD0001-yyyyyyyyyyyy"), 2)
        w3.close()
    finally:
        await swarm.seeder.stop()


async def test_the_seeder_serves_the_info_dictionary_over_ut_metadata(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", {"a.bin": b"x" * 40000, "b": b"y" * 90000})
    try:
        f = MetadataFetcher(
            swarm.torrent.infohash, b"-AD0001-abcdefghijkl", NullReporter(), extra_peers=[("127.0.0.1", swarm.seeder.port)]
        )
        assert await asyncio.wait_for(f.run(), 5) == info_bytes(swarm.raw)
    finally:
        await swarm.seeder.stop()


async def ask_for_metadata(swarm, their_id: int) -> tuple[list[peer.Msg], list[dict]]:
    """Handshake, announce `their_id` as our ut_metadata id, ask for piece 0; what came back and what the loop logged."""
    problems: list[dict] = []
    asyncio.get_running_loop().set_exception_handler(lambda loop, ctx: problems.append(ctx))
    reader, writer = await asyncio.open_connection("127.0.0.1", swarm.seeder.port)
    await peer.handshake(reader, writer, swarm.torrent.infohash, b"-AD0001-abcdefghijkl")
    writer.write(peer.encode_message(peer.extension_handshake({metadata.UT_METADATA: their_id})))
    writer.write(peer.encode_message(metadata.request_piece(2, 0)))  # the seeder's own id for ut_metadata is 2
    writer.write(peer.encode_message(peer.Msg(peer.INTERESTED)))
    writer.write(peer.encode_message(peer.request(0, 0, 100)))  # a fence: its answer comes after anything the above caused
    got: list[peer.Msg] = []
    while not (got and got[-1].id == peer.PIECE):
        got.append(await asyncio.wait_for(peer.read_message(reader), 2))
    writer.close()
    gc.collect()
    return got, problems


@pytest.mark.parametrize("their_id", [0, 256, 1000, 2**40, -1])
async def test_a_peer_with_an_out_of_range_ut_metadata_id_gets_no_data_and_causes_no_traceback(tmp_path: Path, their_id: int) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    swarm.seeder.info = info_bytes(swarm.raw)
    try:
        got, problems = await ask_for_metadata(swarm, their_id)
        assert problems == []
        assert not any(m.id == peer.EXTENDED and m.payload and m.payload[0] != 0 for m in got)  # no ut_metadata answer
    finally:
        await swarm.seeder.stop()


async def test_the_highest_valid_ut_metadata_id_still_works(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    try:
        got, problems = await ask_for_metadata(swarm, 255)
        assert problems == []
        assert any(m.id == peer.EXTENDED and m.payload and m.payload[0] == 255 for m in got)
    finally:
        await swarm.seeder.stop()


async def test_an_interested_peer_is_unchoked_and_gets_the_block_it_asks_for(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    try:
        r, w = await connect(swarm)
        send(w, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r)).id == peer.UNCHOKE
        send(w, peer.request(1, 100, 500))
        m = await next_msg(r)
        assert m.id == peer.PIECE
        assert peer.parse_piece(m.payload) == (1, 100, swarm.payload[16384 + 100 : 16384 + 600])
        assert swarm.seeder.uploaded == 500
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_only_max_unchoked_peers_are_unchoked_and_the_next_one_gets_the_freed_slot(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    swarm.seeder.max_unchoked = 1
    try:
        r1, w1 = await connect(swarm, b"a")
        r2, w2 = await connect(swarm, b"b")
        send(w1, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r1)).id == peer.UNCHOKE
        send(w2, peer.Msg(peer.INTERESTED))
        with pytest.raises(TimeoutError):
            await next_msg(r2, 0.3)  # no free slot: stays choked
        send(w1, peer.Msg(peer.NOT_INTERESTED))
        assert (await next_msg(r1)).id == peer.CHOKE
        assert (await next_msg(r2)).id == peer.UNCHOKE
        w1.close()
        w2.close()
    finally:
        await swarm.seeder.stop()


async def test_a_slot_is_freed_when_the_peer_disconnects(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    swarm.seeder.max_unchoked = 1
    try:
        r1, w1 = await connect(swarm, b"a")
        r2, w2 = await connect(swarm, b"b")
        send(w1, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r1)).id == peer.UNCHOKE
        send(w2, peer.Msg(peer.INTERESTED))
        w1.close()
        assert (await next_msg(r2)).id == peer.UNCHOKE
        w2.close()
    finally:
        await swarm.seeder.stop()


async def test_requests_from_a_choked_peer_are_ignored(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    try:
        r, w = await connect(swarm)
        send(w, peer.request(0, 0, 100))  # never said interested, never unchoked
        with pytest.raises(TimeoutError):
            await next_msg(r, 0.3)
        assert swarm.seeder.blocks_served == 0
        w.close()
    finally:
        await swarm.seeder.stop()


@pytest.mark.parametrize(
    "req",
    [(0, 0, 0), (0, 0, 1 << 20), (99, 0, 10), (0, 40000, 10), (2, 7230, 10), (0, 16380, 16), (0, (1 << 32) - 1, 16384)],
    ids=["zero length", "huge length", "no such piece", "begin past the end", "last piece, past its end", "crosses piece end", "huge begin"],
)
async def test_a_request_outside_the_torrent_hangs_up_and_reads_nothing(tmp_path: Path, req: tuple[int, int, int]) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)  # 40000 bytes in 16384-byte pieces: 3 pieces, the last of 7232 bytes
    try:
        r, w = await connect(swarm)
        send(w, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r)).id == peer.UNCHOKE
        send(w, peer.request(*req))
        assert await until_hung_up(r) == b""
        assert swarm.seeder.blocks_served == 0
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_a_request_for_a_piece_we_do_not_have_hangs_up(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    swarm.seeder.have.discard(1)
    try:
        r, w = await connect(swarm)
        send(w, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r)).id == peer.UNCHOKE
        send(w, peer.request(1, 0, 100))
        assert await until_hung_up(r) == b""
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_a_cancelled_request_is_not_answered(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, delay=0.3)
    try:
        r, w = await connect(swarm)
        send(w, peer.Msg(peer.INTERESTED))
        assert (await next_msg(r)).id == peer.UNCHOKE
        send(w, peer.request(0, 0, 100))  # taken at once by the uploader, waiting out the delay
        send(w, peer.request(0, 100, 100))
        send(w, peer.cancel(0, 100, 100))
        send(w, peer.request(0, 200, 100))  # answered after the first: the cancelled one would have come in between
        begins = [peer.parse_piece((await next_msg(r, 3)).payload)[1] for _ in range(2)]
        assert begins == [0, 200]
        w.close()
    finally:
        await swarm.seeder.stop()


async def test_a_peer_that_goes_silent_is_dropped(tmp_path: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES)
    swarm.seeder.idle_timeout = 0.2
    try:
        r, w = await connect(swarm)
        assert await until_hung_up(r) == b""
        w.close()
    finally:
        await swarm.seeder.stop()
