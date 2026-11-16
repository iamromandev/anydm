import asyncio
import hashlib
import os
import shutil
import time
from pathlib import Path

import pytest
from src.core.errors import DownloadError
from src.core.progress import NullReporter
from src.engine import Job, Kind, engine_for
from src.engine import torrent as engine_mod
from src.engine.torrent import peer
from src.engine.torrent import session as session_mod
from src.engine.torrent.storage import Storage

from tests.engine.torrent.helpers import make_torrent
from tests.engine.torrent.seeding import make_seeder
from tests.engine.torrent.trackers import HttpTracker

FILES = {"a.bin": os.urandom(70000), "d/b.bin": os.urandom(150000), "c.bin": os.urandom(5)}


class Notices(NullReporter):
    def __init__(self) -> None:
        self.notices: list[str] = []
        self.updates: list = []

    def notice(self, msg: str) -> None:
        self.notices.append(msg)

    def update(self, p) -> None:
        self.updates.append(p)


def job(source: str, dest: Path, seed: bool = False) -> Job:
    return Job(source, dest, 4, None, False, seed, False)


@pytest.fixture
def tracker():
    t = HttpTracker([]).start()
    yield t
    t.stop()


async def setup_swarm(tmp_path: Path, tracker: HttpTracker, files=None, **kw):
    swarm = await make_seeder(tmp_path / "seed", files or FILES, announce=tracker.url, **kw)
    tracker.peers = [("127.0.0.1", swarm.seeder.port)]
    f = tmp_path / "x.torrent"
    f.write_bytes(swarm.raw)
    return swarm, f


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


async def test_downloads_a_torrent_file_and_returns_the_paths(tmp_path: Path, tracker: HttpTracker) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker)
    try:
        dest = tmp_path / "dl"
        dest.mkdir()
        paths = await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
        assert paths == [dest.resolve() / "pack" / "a.bin", dest.resolve() / "pack" / "d" / "b.bin", dest.resolve() / "pack" / "c.bin"]
        for p, data in zip(paths, FILES.values(), strict=True):
            assert sha(p) == hashlib.sha256(data).hexdigest()
        assert tracker.queries[0]["event"] == ["started"]
    finally:
        await swarm.seeder.stop()


async def test_second_run_downloads_no_pieces(tmp_path: Path, tracker: HttpTracker) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker)
    try:
        dest = tmp_path / "dl"
        dest.mkdir()
        await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
        served = swarm.seeder.blocks_served
        rep = Notices()
        await engine_for(Kind.TORRENT).run(job(str(f), dest), rep)
        assert swarm.seeder.blocks_served == served
        assert "all pieces already complete" in rep.notices
    finally:
        await swarm.seeder.stop()


async def test_rerun_repairs_damaged_pieces_only(tmp_path: Path, tracker: HttpTracker) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker)
    try:
        dest = tmp_path / "dl"
        dest.mkdir()
        await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
        served = swarm.seeder.blocks_served
        with open(dest / "pack" / "a.bin", "r+b") as fh:
            fh.write(b"\x00" * 100)  # damages piece 0 only
        os.remove(next(dest.glob("*.anydm.json")))  # force a recheck
        await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
        assert swarm.seeder.blocks_served - served == 1
        assert (dest / "pack" / "a.bin").read_bytes() == FILES["a.bin"]
    finally:
        await swarm.seeder.stop()


async def test_hostile_paths_stay_inside_dest(tmp_path: Path, tracker: HttpTracker) -> None:
    files = {"1": b"one" * 5000, "2": b"two" * 5000, "3": b"three" * 5000}
    raw_paths = {"1": [b"..", b"..", b"etc", b"x"], "2": [b"/abs", b"y"], "3": [b"a\\..\\z"]}
    swarm, f = await setup_swarm(tmp_path, tracker, files, name="../../evil", raw_paths=raw_paths)
    try:
        dest = tmp_path / "out" / "dl"
        dest.mkdir(parents=True)
        paths = await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
        assert len(paths) == 3
        for p in paths:
            assert p.is_relative_to(dest.resolve())
        assert sorted(x.name for x in (tmp_path / "out").iterdir()) == ["dl"]
        assert not (tmp_path / "evil").exists() and not (tmp_path / "etc").exists()
        assert not Path("/abs").exists()
    finally:
        await swarm.seeder.stop()


async def test_torrent_url_is_fetched_over_http(tmp_path: Path, tracker: HttpTracker, serve) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker)
    srv = serve(f.read_bytes())
    try:
        dest = tmp_path / "dl"
        dest.mkdir()
        paths = await engine_for(Kind.TORRENT).run(job(srv.url("/x.torrent"), dest), NullReporter())
        assert len(paths) == 3
    finally:
        await swarm.seeder.stop()


async def test_oversized_torrent_url_is_refused(tmp_path: Path, serve) -> None:
    srv = serve(b"x" * ((10 << 20) + 1))
    with pytest.raises(DownloadError, match="10 MiB"):
        await engine_for(Kind.TORRENT).run(job(srv.url("/x.torrent"), tmp_path), NullReporter())


async def test_http_error_fetching_the_torrent_names_the_status(tmp_path: Path, serve) -> None:
    srv = serve(b"", missing={"/x.torrent"})
    with pytest.raises(DownloadError, match="404"):
        await engine_for(Kind.TORRENT).run(job(srv.url("/x.torrent"), tmp_path), NullReporter())


async def leech(port: int, swarm, piece: int = 0) -> bytes:
    """Fetch one piece from a seeding anydm over the wire, as a plain peer."""
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    try:
        await peer.handshake(reader, writer, swarm.torrent.infohash, b"-XX0001-abcdefghijkl")
        writer.write(peer.encode_message(peer.Msg(peer.INTERESTED)))
        got = b""
        asked = False
        while len(got) < swarm.torrent.piece_size(piece):
            m = await asyncio.wait_for(peer.read_message(reader), 5)
            if m.id == peer.UNCHOKE and not asked:
                asked = True
                writer.write(peer.encode_message(peer.request(piece, 0, swarm.torrent.piece_size(piece))))
            elif m.id == peer.PIECE:
                got += peer.parse_piece(m.payload)[2]
        return got
    finally:
        writer.close()


async def seeding_query(tracker: HttpTracker, timeout: float = 10.0) -> dict[str, list[str]]:
    """The announce of the seeding phase (`started` with nothing left to download), once it has been made."""
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        for q in list(tracker.queries):
            if q.get("event") == ["started"] and q.get("left") == ["0"]:
                return q
        await asyncio.sleep(0.02)
    raise AssertionError("no seeding announce")


async def test_seed_flag_keeps_seeding_after_the_download_until_cancelled(
    tmp_path: Path, tracker: HttpTracker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(engine_mod, "LISTEN_PORT", 0)
    swarm, f = await setup_swarm(tmp_path, tracker)
    reporter = Notices()
    dest = tmp_path / "dl"
    dest.mkdir()
    task = asyncio.create_task(engine_for(Kind.TORRENT).run(job(str(f), dest, seed=True), reporter))
    try:
        for _ in range(100):
            if any(p.phase == "seeding" for p in reporter.updates):
                break
            await asyncio.sleep(0.1)
        assert any(p.phase == "seeding" for p in reporter.updates)
        assert not task.done()  # the download is complete, and it still runs
        assert sha(dest / "pack" / "a.bin") == hashlib.sha256(FILES["a.bin"]).hexdigest()
        port = int((await seeding_query(tracker))["port"][0])
        assert await leech(port, swarm) == swarm.payload[: swarm.torrent.piece_size(0)]
        task.cancel()
        paths = await asyncio.wait_for(task, 5)  # a cancel while seeding is how the user ends it: a normal result
        assert paths == [dest.resolve() / "pack" / "a.bin", dest.resolve() / "pack" / "d" / "b.bin", dest.resolve() / "pack" / "c.bin"]
        assert tracker.queries[-1]["event"] == ["stopped"]
        with pytest.raises(OSError):
            await asyncio.open_connection("127.0.0.1", port)  # the listening port is closed
    finally:
        task.cancel()
        await swarm.seeder.stop()


async def test_without_seed_the_engine_returns_at_completion_and_listens_nowhere(
    tmp_path: Path, tracker: HttpTracker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(engine_mod, "LISTEN_PORT", 0)
    swarm, f = await setup_swarm(tmp_path, tracker)
    try:
        reporter = Notices()
        (tmp_path / "dl").mkdir()
        await asyncio.wait_for(engine_for(Kind.TORRENT).run(job(str(f), tmp_path / "dl"), reporter), 20)
        assert not any(p.phase == "seeding" for p in reporter.updates)
    finally:
        await swarm.seeder.stop()


async def test_cancel_during_the_download_still_raises_cancelled_error(tmp_path: Path, tracker: HttpTracker) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker, delay=0.2)
    try:
        (tmp_path / "dl").mkdir()
        task = asyncio.create_task(engine_for(Kind.TORRENT).run(job(str(f), tmp_path / "dl", seed=True), NullReporter()))
        await asyncio.sleep(1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    finally:
        await swarm.seeder.stop()


async def test_garbage_torrent_is_a_download_error(tmp_path: Path) -> None:
    f = tmp_path / "bad.torrent"
    f.write_bytes(b"not bencode")
    with pytest.raises(DownloadError, match=r"not a valid \.torrent"):
        await engine_for(Kind.TORRENT).run(job(str(f), tmp_path), NullReporter())


async def test_missing_torrent_file_is_a_download_error(tmp_path: Path) -> None:
    with pytest.raises(DownloadError, match="cannot read"):
        await engine_for(Kind.TORRENT).run(job(str(tmp_path / "nope.torrent"), tmp_path), NullReporter())


async def test_second_concurrent_run_is_refused(tmp_path: Path, tracker: HttpTracker) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker)
    await swarm.seeder.stop()
    from src.engine.http.engine import _Lock

    dest = tmp_path / "dl"
    dest.mkdir()
    lock = _Lock(dest / f"{swarm.torrent.infohash.hex()}.anydm.lock")
    lock.acquire()
    try:
        with pytest.raises(DownloadError, match="already in progress"):
            await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
    finally:
        lock.release()


def events(tracker: HttpTracker) -> list[str | None]:
    return [q.get("event", [None])[0] for q in tracker.queries]


async def test_a_fresh_download_announces_started_completed_and_stopped(tmp_path: Path, tracker: HttpTracker) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker)
    try:
        dest = tmp_path / "dl"
        dest.mkdir()
        await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
        assert events(tracker) == ["started", "completed", "stopped"]
    finally:
        await swarm.seeder.stop()


async def test_a_run_that_downloads_nothing_does_not_announce_completed(tmp_path: Path, tracker: HttpTracker) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker)
    try:
        dest = tmp_path / "dl"
        dest.mkdir()
        await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
        tracker.queries.clear()
        await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
        assert "completed" not in events(tracker)
    finally:
        await swarm.seeder.stop()


BIG = {"big.bin": os.urandom(16384 * 300 + 5000)}  # the last piece is short


async def complete_copy(tmp_path: Path, tracker: HttpTracker):
    swarm, f = await setup_swarm(tmp_path, tracker, BIG)
    dest = tmp_path / "dl"
    dest.mkdir()
    await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
    os.remove(next(dest.glob("*.anydm.json")))  # the next run has to recheck all 300 pieces
    return swarm, f, dest


async def test_the_checking_progress_never_exceeds_the_total(tmp_path: Path, tracker: HttpTracker) -> None:
    swarm, f, dest = await complete_copy(tmp_path, tracker)
    try:
        rep = Notices()
        await engine_for(Kind.TORRENT).run(job(str(f), dest), rep)
        checking = [u for u in rep.updates if u.phase == "checking"]
        assert checking and all(u.done <= u.total for u in checking)
        assert checking[-1].done == checking[-1].total
    finally:
        await swarm.seeder.stop()


async def test_cancelling_during_the_recheck_stops_within_a_second_and_leaves_no_thread_behind(
    tmp_path: Path, tracker: HttpTracker, monkeypatch: pytest.MonkeyPatch
) -> None:
    swarm, f, dest = await complete_copy(tmp_path, tracker)
    real, real_close = Storage.piece_ok, Storage.close
    calls, busy, closed_while_busy = 0, 0, []

    def slow(self, index: int) -> bool:
        nonlocal calls, busy
        calls += 1
        busy += 1
        try:
            time.sleep(0.02)  # 300 pieces: 6 s if nothing stops it
            return real(self, index)
        finally:
            busy -= 1

    def close(self) -> None:
        closed_while_busy.append(busy)
        real_close(self)

    monkeypatch.setattr(Storage, "piece_ok", slow)
    monkeypatch.setattr(Storage, "close", close)
    rep = Notices()
    try:
        run = asyncio.create_task(engine_for(Kind.TORRENT).run(job(str(f), dest), rep))
        while len([u for u in rep.updates if u.phase == "checking"]) < 5:
            await asyncio.sleep(0.01)
        start = time.monotonic()
        run.cancel()
        with pytest.raises(asyncio.CancelledError):
            await run
        assert time.monotonic() - start < 1.0
        seen = calls
        await asyncio.sleep(0.2)
        assert calls == seen  # the thread really stopped
        assert closed_while_busy and set(closed_while_busy) == {0}  # and was done before the files were closed
    finally:
        await swarm.seeder.stop()


async def test_an_existing_foreign_file_is_never_touched_and_the_torrent_goes_under_a_new_name(
    tmp_path: Path, tracker: HttpTracker
) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker)
    try:
        dest = tmp_path / "dl"
        (dest / "pack").mkdir(parents=True)
        mine = dest / "pack" / "a.bin"
        mine.write_bytes(b"my own file" * 20000)  # longer than the torrent's a.bin
        rep = Notices()
        paths = await engine_for(Kind.TORRENT).run(job(str(f), dest), rep)
        assert mine.read_bytes() == b"my own file" * 20000
        assert paths[0] == dest.resolve() / "pack (1)" / "a.bin"
        for p, data in zip(paths, FILES.values(), strict=True):
            assert sha(p) == hashlib.sha256(data).hexdigest()
        assert any("pack (1)" in n for n in rep.notices)
    finally:
        await swarm.seeder.stop()


async def test_a_rerun_after_an_interrupted_start_keeps_using_the_same_new_name(
    tmp_path: Path, tracker: HttpTracker, monkeypatch: pytest.MonkeyPatch
) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker)
    try:
        dest = tmp_path / "dl"
        (dest / "pack").mkdir(parents=True)
        (dest / "pack" / "a.bin").write_bytes(b"not part of the torrent")
        real = session_mod.Session.run

        async def die(self) -> None:
            raise RuntimeError("killed before the first save")

        monkeypatch.setattr(session_mod.Session, "run", die)
        with pytest.raises(RuntimeError):
            await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
        monkeypatch.setattr(session_mod.Session, "run", real)
        paths = await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
        assert paths[0] == dest.resolve() / "pack (1)" / "a.bin"
        assert not (dest / "pack (2)").exists()
    finally:
        await swarm.seeder.stop()


async def test_a_partial_copy_without_a_record_is_resumed_not_set_aside(tmp_path: Path, tracker: HttpTracker) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker)
    try:
        dest = tmp_path / "dl"
        dest.mkdir()
        store = Storage(swarm.torrent, dest)
        store.write_piece(0, swarm.storage.read(0, 0, swarm.torrent.piece_size(0)))
        store.close()
        paths = await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
        assert paths[0] == dest.resolve() / "pack" / "a.bin"
        assert not (dest / "pack (1)").exists()
    finally:
        await swarm.seeder.stop()


async def test_a_piece_length_over_32_mib_is_refused_with_a_download_error(tmp_path: Path) -> None:
    raw, _ = make_torrent({"a": b"abc"}, piece_len=(32 << 20) + 1)
    (tmp_path / "big.torrent").write_bytes(raw)
    with pytest.raises(DownloadError, match="piece length") as e:
        await engine_for(Kind.TORRENT).run(job(str(tmp_path / "big.torrent"), tmp_path), NullReporter())
    assert e.value.exit_code == 1


async def test_not_enough_free_disk_space_is_a_download_error(
    tmp_path: Path, tracker: HttpTracker, monkeypatch: pytest.MonkeyPatch
) -> None:
    swarm, f = await setup_swarm(tmp_path, tracker)
    await swarm.seeder.stop()
    monkeypatch.setattr(shutil, "disk_usage", lambda p: shutil._ntuple_diskusage(1000, 1000, 0))
    dest = tmp_path / "dl"
    dest.mkdir()
    with pytest.raises(DownloadError, match="disk space") as e:
        await engine_for(Kind.TORRENT).run(job(str(f), dest), NullReporter())
    assert e.value.exit_code == 1
    assert not (dest / "pack").exists()
