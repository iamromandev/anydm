"""`TorrentEngine` with a magnet link: metadata from peers, the cache in dest, the DHT, and the phases reported."""

import asyncio
import hashlib
import os
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest
from src.core.errors import DownloadError, UsageError
from src.engine import Job, Kind, engine_for
from src.engine import torrent as engine_mod
from src.engine.torrent.bencode import Value, decode, encode
from src.engine.torrent.metainfo import Torrent

from tests.engine.torrent.fake_dht import chain
from tests.engine.torrent.helpers import Recorder, info_bytes, make_torrent
from tests.engine.torrent.seeding import make_seeder
from tests.engine.torrent.stubs import StubPeer, serve_metadata
from tests.engine.torrent.trackers import HttpTracker

FILES = {"a.bin": os.urandom(70000), "d/b.bin": os.urandom(150000), "c.bin": os.urandom(5)}


def as_dict(v: Value) -> dict[bytes, Any]:
    assert isinstance(v, dict)
    return v


def job(source: str, dest: Path, seed: bool = False) -> Job:
    return Job(source, dest, 4, None, False, seed, False)


def magnet_for(swarm, *trackers: str, name: str | None = None) -> str:
    uri = "magnet:?xt=urn:btih:" + swarm.torrent.infohash.hex()
    if name:
        uri += "&dn=" + name
    return uri + "".join("&tr=" + t.replace(":", "%3A").replace("/", "%2F") for t in trackers)


@pytest.fixture
def tracker():
    t = HttpTracker([]).start()
    yield t
    t.stop()


@pytest.fixture
async def dest(tmp_path: Path) -> Path:
    d = tmp_path / "dl"
    d.mkdir()
    return d


async def with_tracker(tmp_path: Path, tracker: HttpTracker, files=None, **kw):
    swarm = await make_seeder(tmp_path / "seed", files or FILES, announce=None, **kw)
    tracker.peers = [("127.0.0.1", swarm.seeder.port)]
    return swarm


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


async def test_a_magnet_with_a_tracker_downloads_the_files(tmp_path: Path, tracker: HttpTracker, dest: Path) -> None:
    swarm = await with_tracker(tmp_path, tracker)
    try:
        paths = await engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), dest), Recorder())
        assert [p.relative_to(dest.resolve()).as_posix() for p in paths] == ["pack/a.bin", "pack/d/b.bin", "pack/c.bin"]
        for p, data in zip(paths, FILES.values(), strict=True):
            assert sha(p) == hashlib.sha256(data).hexdigest()
    finally:
        await swarm.seeder.stop()


async def test_the_metadata_is_cached_in_dest_as_infohash_dot_torrent(tmp_path: Path, tracker: HttpTracker, dest: Path) -> None:
    swarm = await with_tracker(tmp_path, tracker)
    try:
        await engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), dest), Recorder())
        cached = dest / f"{swarm.torrent.infohash.hex()}.torrent"
        assert cached.is_file()
        meta = as_dict(decode(cached.read_bytes()))
        assert encode(meta[b"info"]) == info_bytes(swarm.raw)
    finally:
        await swarm.seeder.stop()


async def test_a_rerun_skips_the_metadata_phase_and_downloads_no_pieces(tmp_path: Path, tracker: HttpTracker, dest: Path) -> None:
    swarm = await with_tracker(tmp_path, tracker)
    try:
        uri = magnet_for(swarm, tracker.url)
        await engine_for(Kind.TORRENT).run(job(uri, dest), Recorder())
        served, connections = swarm.seeder.blocks_served, swarm.seeder.connections
        rec = Recorder()
        paths = await engine_for(Kind.TORRENT).run(job(uri, dest), rec)
        assert len(paths) == 3
        assert swarm.seeder.blocks_served == served and swarm.seeder.connections == connections
        assert not [u for u in rec.updates if u.phase == "metadata"]
        assert "all pieces already complete" in rec.notices
    finally:
        await swarm.seeder.stop()


async def test_a_rerun_after_an_interrupt_resumes_from_the_cached_metadata(tmp_path: Path, tracker: HttpTracker, dest: Path) -> None:
    big = {"big.bin": os.urandom(400_000)}
    swarm = await with_tracker(tmp_path, tracker, big, delay=0.02)
    try:
        uri = magnet_for(swarm, tracker.url)
        task = asyncio.create_task(engine_for(Kind.TORRENT).run(job(uri, dest), Recorder()))
        deadline = time.monotonic() + 20
        while swarm.seeder.blocks_served < 8 and time.monotonic() < deadline:
            await asyncio.sleep(0.02)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        before = swarm.seeder.blocks_served
        swarm.seeder.delay = 0.0
        rec = Recorder()
        paths = await engine_for(Kind.TORRENT).run(job(uri, dest), rec)
        assert sha(paths[0]) == hashlib.sha256(big["big.bin"]).hexdigest()
        assert not [u for u in rec.updates if u.phase == "metadata"]
        total = -(-400_000 // 16384)
        kept = [n for n in rec.notices if n.endswith(f"of {total} pieces already complete")]
        assert kept and swarm.seeder.blocks_served - before < total  # it resumed rather than starting over
    finally:
        await swarm.seeder.stop()


@pytest.mark.parametrize("damage", ["garbage", "other torrent", "truncated"])
async def test_a_damaged_cache_is_ignored_and_replaced(tmp_path: Path, tracker: HttpTracker, dest: Path, damage: str) -> None:
    swarm = await with_tracker(tmp_path, tracker)
    try:
        cached = dest / f"{swarm.torrent.infohash.hex()}.torrent"
        other, _ = make_torrent({"zzz": b"q" * 5000})
        cached.write_bytes({"garbage": b"not bencode at all", "other torrent": other, "truncated": swarm.raw[:60]}[damage])
        rec = Recorder()
        paths = await engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), dest), rec)
        assert len(paths) == 3 and paths[0].name == "a.bin"
        assert [u for u in rec.updates if u.phase == "metadata"]  # it fetched again
        assert encode(as_dict(decode(cached.read_bytes()))[b"info"]) == info_bytes(swarm.raw)
    finally:
        await swarm.seeder.stop()


async def test_progress_goes_from_the_metadata_phase_to_downloading_and_names_the_magnet(
    tmp_path: Path, tracker: HttpTracker, dest: Path
) -> None:
    swarm = await with_tracker(tmp_path, tracker, delay=0.01)
    try:
        rec = Recorder()
        await engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url, name="My+Film"), dest), rec)
        phases = [u.phase for u in rec.updates]
        assert phases[0] == "metadata" and "downloading" in phases
        assert phases.index("downloading") > max(i for i, p in enumerate(phases) if p == "metadata")
        assert next(u for u in rec.updates if u.phase == "metadata").name == "My Film"
        assert any(u.phase == "metadata" and u.peers == 1 for u in rec.updates)
    finally:
        await swarm.seeder.stop()


async def test_a_magnet_with_no_tracker_finds_its_peer_through_the_dht(
    tmp_path: Path, dest: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=None)
    nodes = await chain(swarm.torrent.infohash, [("127.0.0.1", swarm.seeder.port)], hops=2, decoys=2)
    monkeypatch.setenv("ANYDM_DHT_BOOTSTRAP", f"127.0.0.1:{nodes[0].port}")
    try:
        paths = await asyncio.wait_for(engine_for(Kind.TORRENT).run(job(magnet_for(swarm), dest), Recorder()), 30)
        for p, data in zip(paths, FILES.values(), strict=True):
            assert sha(p) == hashlib.sha256(data).hexdigest()
        assert all(q != b"announce_peer" for n in nodes for q, _ in n.queries)
    finally:
        await swarm.seeder.stop()
        for n in nodes:
            n.stop()


async def test_a_torrent_file_with_no_trackers_uses_the_dht_instead_of_failing(
    tmp_path: Path, dest: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=None)
    f = tmp_path / "n.torrent"
    f.write_bytes(swarm.raw)
    nodes = await chain(swarm.torrent.infohash, [("127.0.0.1", swarm.seeder.port)], hops=1)
    monkeypatch.setenv("ANYDM_DHT_BOOTSTRAP", f"127.0.0.1:{nodes[0].port}")
    try:
        paths = await asyncio.wait_for(engine_for(Kind.TORRENT).run(job(str(f), dest), Recorder()), 30)
        assert len(paths) == 3
    finally:
        await swarm.seeder.stop()
        for n in nodes:
            n.stop()


async def test_a_torrent_file_with_no_trackers_and_no_dht_waits_for_peers_with_a_notice(
    tmp_path: Path, dest: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from src.engine.torrent import session as session_mod

    monkeypatch.setattr(session_mod, "WAIT_NOTICE_EVERY", 0.1)
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=None)
    await swarm.seeder.stop()
    f = tmp_path / "n.torrent"
    f.write_bytes(swarm.raw)
    rec = Recorder()
    task = asyncio.create_task(engine_for(Kind.TORRENT).run(job(str(f), dest), rec))
    await asyncio.sleep(0.5)
    assert not task.done()
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)
    assert any(n.startswith("waiting for peers (0 trackers") for n in rec.notices)


async def test_ctrl_c_during_the_metadata_phase_stops_within_a_second(tmp_path: Path, dest: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=None)
    await swarm.seeder.stop()
    rec = Recorder()
    uri = magnet_for(swarm, "http://127.0.0.1:1/announce?passkey=SECRET")
    task = asyncio.create_task(engine_for(Kind.TORRENT).run(job(uri, dest), rec))
    await asyncio.sleep(0.5)
    assert not task.done()
    start = time.monotonic()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert time.monotonic() - start < 1.0
    assert "SECRET" not in "".join(rec.notices)


@pytest.mark.parametrize("uri", ["magnet:?dn=x", "magnet:?xt=urn:btih:zz", "magnet:?xt=urn:btmh:1220" + "ab" * 32])
async def test_a_bad_magnet_is_a_usage_error(dest: Path, uri: str) -> None:
    with pytest.raises(UsageError):
        await engine_for(Kind.TORRENT).run(job(uri, dest), Recorder())


async def test_seed_with_a_magnet_keeps_seeding_after_the_download(
    tmp_path: Path, tracker: HttpTracker, dest: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(engine_mod, "LISTEN_PORT", 0)
    swarm = await with_tracker(tmp_path, tracker)
    rec = Recorder()
    task = asyncio.create_task(engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), dest, seed=True), rec))
    try:
        for _ in range(100):
            if any(u.phase == "seeding" for u in rec.updates):
                break
            await asyncio.sleep(0.1)
        assert any(u.phase == "seeding" for u in rec.updates)
        assert not task.done()
        task.cancel()
        paths = await asyncio.wait_for(task, 5)
        assert len(paths) == 3
    finally:
        task.cancel()
        await swarm.seeder.stop()


async def test_verified_metadata_that_is_a_v2_torrent_is_a_download_error(dest: Path, tracker: HttpTracker) -> None:
    raw, _ = make_torrent({"a": b"x" * 20000})
    info = as_dict(decode(info_bytes(raw)))
    info[b"meta version"] = 2
    blob = encode(info)
    ih = hashlib.sha1(blob).digest()
    stub = await StubPeer(
        cast("Torrent", SimpleNamespace(infohash=ih, piece_count=1)), serve_metadata(blob), has=set(), initial_bitfield=False
    ).start()
    tracker.peers = [stub.addr]
    try:
        uri = f"magnet:?xt=urn:btih:{ih.hex()}&tr=" + tracker.url.replace(":", "%3A").replace("/", "%2F")
        with pytest.raises(DownloadError, match="v2"):
            await asyncio.wait_for(engine_for(Kind.TORRENT).run(job(uri, dest), Recorder()), 20)
    finally:
        await stub.stop()


async def test_hostile_paths_in_fetched_metadata_stay_inside_dest(tmp_path: Path, tracker: HttpTracker) -> None:
    files = {"1": b"one" * 5000, "2": b"two" * 5000}
    swarm = await with_tracker(
        tmp_path, tracker, files, name="../../evil", raw_paths={"1": [b"..", b"..", b"etc", b"x"], "2": [b"/abs", b"y"]}
    )
    try:
        dest = tmp_path / "out" / "dl"
        dest.mkdir(parents=True)
        paths = await engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), dest), Recorder())
        assert len(paths) == 2 and all(p.is_relative_to(dest.resolve()) for p in paths)
        assert [x.name for x in (tmp_path / "out").iterdir()] == ["dl"]
    finally:
        await swarm.seeder.stop()


async def test_metadata_from_a_lying_peer_is_not_accepted_and_the_other_peer_wins(
    tmp_path: Path, tracker: HttpTracker, dest: Path
) -> None:
    swarm = await with_tracker(tmp_path, tracker)
    liar = await StubPeer(swarm.torrent, serve_metadata(info_bytes(swarm.raw), corrupt={0}), has=set(), initial_bitfield=False).start()
    tracker.peers = [liar.addr, ("127.0.0.1", swarm.seeder.port)]
    try:
        paths = await asyncio.wait_for(engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), dest), Recorder()), 30)
        assert len(paths) == 3
    finally:
        await swarm.seeder.stop()
        await liar.stop()



# --- N1: a bootstrap setting that parses to nothing ---


def test_a_bootstrap_setting_with_no_valid_host_port_turns_the_dht_off_with_a_notice(monkeypatch: pytest.MonkeyPatch) -> None:
    from src.engine.torrent import _dht

    monkeypatch.setenv("ANYDM_DHT_BOOTSTRAP", "garbage, host:99999")
    rec = Recorder()
    assert _dht(rec) is None
    assert rec.notices == ["ANYDM_DHT_BOOTSTRAP has no valid host:port; DHT off"]


def test_a_bootstrap_setting_that_is_empty_turns_the_dht_off_silently_and_unset_uses_the_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    from src.engine.torrent import _dht

    rec = Recorder()
    monkeypatch.setenv("ANYDM_DHT_BOOTSTRAP", "")
    assert _dht(rec) is None and rec.notices == []
    monkeypatch.delenv("ANYDM_DHT_BOOTSTRAP")
    client = _dht(rec)
    assert client is not None and client.allow_local is False


# --- S3: the peers that gave the metadata are reused by the download ---


async def test_the_peers_that_gave_the_metadata_are_handed_to_the_download_session(
    tmp_path: Path, tracker: HttpTracker, dest: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from src.engine import torrent as torrent_mod
    from src.engine.torrent.session import Session

    given: list = []

    class Spy(Session):
        def __init__(self, *a: Any, **kw: Any) -> None:
            given.append(kw.get("extra_peers"))
            super().__init__(*a, **kw)

    monkeypatch.setattr(torrent_mod, "Session", Spy)
    swarm = await with_tracker(tmp_path, tracker)
    try:
        await engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), dest), Recorder())
        assert given == [[("127.0.0.1", swarm.seeder.port)]]
    finally:
        await swarm.seeder.stop()


async def test_a_run_from_the_cache_has_no_peers_to_hand_over(
    tmp_path: Path, tracker: HttpTracker, dest: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from src.engine import torrent as torrent_mod
    from src.engine.torrent.session import Session

    given: list = []

    class Spy(Session):
        def __init__(self, *a: Any, **kw: Any) -> None:
            given.append(kw.get("extra_peers"))
            super().__init__(*a, **kw)

    swarm = await with_tracker(tmp_path, tracker)
    try:
        uri = magnet_for(swarm, tracker.url)
        await engine_for(Kind.TORRENT).run(job(uri, dest), Recorder())
        monkeypatch.setattr(torrent_mod, "Session", Spy)
        await engine_for(Kind.TORRENT).run(job(uri, dest), Recorder())
        assert given == [[]]
    finally:
        await swarm.seeder.stop()


# --- S5: a private torrent's infohash is asked for in the DHT during the metadata phase only ---


@pytest.mark.parametrize("private", [True, False])
async def test_the_dht_is_used_after_the_metadata_only_for_a_torrent_that_is_not_private(
    tmp_path: Path, dest: Path, monkeypatch: pytest.MonkeyPatch, private: bool
) -> None:
    from src.engine import torrent as torrent_mod
    from src.engine.torrent.session import Session

    swarm = await make_seeder(tmp_path / "seed", FILES, announce=None, private=private, delay=0.02)
    nodes = await chain(swarm.torrent.infohash, [("127.0.0.1", swarm.seeder.port)], hops=1)
    monkeypatch.setenv("ANYDM_DHT_BOOTSTRAP", f"127.0.0.1:{nodes[0].port}")
    asked: list[int] = []

    class Spy(Session):
        def __init__(self, *a: Any, **kw: Any) -> None:
            asked.append(sum(len(n.queries) for n in nodes))  # what the metadata phase cost
            super().__init__(*a, **kw)

    monkeypatch.setattr(torrent_mod, "Session", Spy)
    try:
        await asyncio.wait_for(engine_for(Kind.TORRENT).run(job(magnet_for(swarm), dest), Recorder()), 30)
        total = sum(len(n.queries) for n in nodes)
        assert asked[0] > 0  # the metadata phase did ask the DHT (README says so)
        assert (total == asked[0]) if private else (total > asked[0])
    finally:
        await swarm.seeder.stop()
        for n in nodes:
            n.stop()


# --- S6: a cache file that is not a plain, reasonably sized file is ignored, never read ---


def plant(kind: str, cached: Path, swarm, tmp_path: Path) -> None:
    if kind == "symlink":
        real = tmp_path / "elsewhere.torrent"
        real.write_bytes(swarm.raw)  # even a perfectly good torrent is not read through a link
        cached.symlink_to(real)
    elif kind == "dangling symlink":
        cached.symlink_to(tmp_path / "nowhere")
    elif kind == "fifo":
        os.mkfifo(cached)
    elif kind == "oversized":
        with cached.open("wb") as f:
            f.truncate(20 << 20)  # sparse


@pytest.mark.parametrize("kind", ["symlink", "dangling symlink", "fifo", "oversized"])
async def test_an_untrustworthy_cache_file_is_ignored_with_a_notice_and_the_metadata_fetched_again(
    tmp_path: Path, tracker: HttpTracker, dest: Path, kind: str
) -> None:
    swarm = await with_tracker(tmp_path, tracker)
    try:
        cached = dest / f"{swarm.torrent.infohash.hex()}.torrent"
        plant(kind, cached, swarm, tmp_path)
        rec = Recorder()
        paths = await asyncio.wait_for(engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), dest), rec), 20)
        assert len(paths) == 3
        assert [u for u in rec.updates if u.phase == "metadata"]  # fetched from peers
        assert any("cached metadata" in n and "ignoring" in n for n in rec.notices)
        assert str(dest) not in "".join(rec.notices)
        assert cached.is_file() and not cached.is_symlink()  # replaced by the fresh copy
    finally:
        await swarm.seeder.stop()


async def test_a_cache_that_is_a_device_or_other_special_file_is_ignored_with_a_notice(
    tmp_path: Path, tracker: HttpTracker, dest: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import stat as stat_mod

    swarm = await with_tracker(tmp_path, tracker)
    try:
        cached = dest / f"{swarm.torrent.infohash.hex()}.torrent"
        cached.write_bytes(swarm.raw)  # a good file, but fstat will say it is a character device
        real_fstat = os.fstat

        def fstat(fd: int) -> os.stat_result:
            st = real_fstat(fd)
            return os.stat_result((stat_mod.S_IFCHR | 0o600, *st[1:]))

        monkeypatch.setattr(os, "fstat", fstat)
        rec = Recorder()
        await engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), dest), rec)
        assert any("cached metadata" in n and "ignoring" in n for n in rec.notices)
        assert [u for u in rec.updates if u.phase == "metadata"]
    finally:
        await swarm.seeder.stop()


async def test_a_missing_cache_file_gets_no_notice(tmp_path: Path, tracker: HttpTracker, dest: Path) -> None:
    swarm = await with_tracker(tmp_path, tracker)
    try:
        rec = Recorder()
        await engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), dest), rec)
        assert not any("cached metadata" in n for n in rec.notices)
    finally:
        await swarm.seeder.stop()


async def test_a_good_cache_is_still_used_and_parsed_off_the_event_loop(tmp_path: Path, tracker: HttpTracker, dest: Path) -> None:
    import threading

    from src.engine import torrent as torrent_mod

    swarm = await with_tracker(tmp_path, tracker)
    try:
        uri = magnet_for(swarm, tracker.url)
        await engine_for(Kind.TORRENT).run(job(uri, dest), Recorder())
        threads: list[str] = []
        real_parse = torrent_mod.parse

        def spying(raw: bytes) -> Torrent:
            threads.append(threading.current_thread().name)
            return real_parse(raw)

        torrent_mod.parse = spying  # ty: ignore[invalid-assignment]
        try:
            rec = Recorder()
            await engine_for(Kind.TORRENT).run(job(uri, dest), rec)
        finally:
            torrent_mod.parse = real_parse
        assert not [u for u in rec.updates if u.phase == "metadata"]
        assert threads and "MainThread" not in threads
    finally:
        await swarm.seeder.stop()


async def test_ctrl_c_still_works_with_a_fifo_in_place_of_the_cache(tmp_path: Path, dest: Path) -> None:
    swarm = await make_seeder(tmp_path / "seed", FILES, announce=None)
    await swarm.seeder.stop()
    os.mkfifo(dest / f"{swarm.torrent.infohash.hex()}.torrent")
    rec = Recorder()
    uri = magnet_for(swarm, "http://127.0.0.1:1/announce")
    task = asyncio.create_task(engine_for(Kind.TORRENT).run(job(uri, dest), rec))
    await asyncio.sleep(0.5)
    assert not task.done() and any("cached metadata" in n for n in rec.notices)  # it moved on to fetching
    start = time.monotonic()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert time.monotonic() - start < 1.0


async def test_an_upper_case_magnet_scheme_is_a_magnet_for_the_engine_too(tmp_path: Path, tracker: HttpTracker, dest: Path) -> None:
    swarm = await with_tracker(tmp_path, tracker)
    try:
        uri = "MAGNET" + magnet_for(swarm, tracker.url)[len("magnet") :]
        paths = await asyncio.wait_for(engine_for(Kind.TORRENT).run(job(uri, dest), Recorder()), 20)
        assert len(paths) == 3
    finally:
        await swarm.seeder.stop()


async def test_a_second_session_leeches_by_magnet_from_a_first_one_that_is_seeding(
    tmp_path: Path, tracker: HttpTracker, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The metadata comes from the seeding anydm itself: the original seeder is gone, and the magnet has no other peer."""
    monkeypatch.setattr(engine_mod, "LISTEN_PORT", 0)
    swarm = await make_seeder(tmp_path / "origin", FILES, announce=tracker.url)
    tracker.peers = [("127.0.0.1", swarm.seeder.port)]
    f = tmp_path / "x.torrent"
    f.write_bytes(swarm.raw)
    first_dest, second_dest = tmp_path / "first", tmp_path / "second"
    first_dest.mkdir()
    second_dest.mkdir()
    first = asyncio.create_task(engine_for(Kind.TORRENT).run(job(str(f), first_dest, seed=True), Recorder()))
    try:
        port = 0
        for _ in range(500):  # the announce of the seeding phase: started, nothing left
            seeding = [q for q in list(tracker.queries) if q.get("event") == ["started"] and q.get("left") == ["0"]]
            if seeding:
                port = int(seeding[0]["port"][0])
                break
            await asyncio.sleep(0.02)
        assert port
        await swarm.seeder.stop()  # nobody but the first session can serve the second one now
        tracker.peers = [("127.0.0.1", port)]
        paths = await asyncio.wait_for(
            engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), second_dest), Recorder()), 30
        )
        for p, data in zip(paths, FILES.values(), strict=True):
            assert sha(p) == hashlib.sha256(data).hexdigest()
        cached = as_dict(decode((second_dest / f"{swarm.torrent.infohash.hex()}.torrent").read_bytes()))
        assert encode(cached[b"info"]) == info_bytes(swarm.raw)  # the very info dictionary, as the first session got it
    finally:
        first.cancel()
        await asyncio.gather(first, return_exceptions=True)
        await swarm.seeder.stop()


async def test_the_metadata_phase_announces_no_port_because_nothing_listens(
    tmp_path: Path, tracker: HttpTracker, dest: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(engine_mod, "LISTEN_PORT", 54321)  # not listening: only --seed ever listens
    swarm = await with_tracker(tmp_path, tracker)
    try:
        await engine_for(Kind.TORRENT).run(job(magnet_for(swarm, tracker.url), dest), Recorder())
        assert tracker.queries and {q["port"][0] for q in tracker.queries} == {"0"}
    finally:
        await swarm.seeder.stop()
