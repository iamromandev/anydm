"""`anydm get file.torrent` end to end: a real subprocess against a local tracker and seeder (the heavier cases;
the quick ones run in test_cli_smoke)."""

import hashlib
import os
import signal
import time
from pathlib import Path

import pytest
from src.engine.torrent.metainfo import parse

from tests.engine.torrent.cli_support import anydm, run, sha
from tests.engine.torrent.helpers import make_torrent

pytestmark = pytest.mark.torrent

FILES = {"a.bin": os.urandom(300_000), "sub/b.bin": os.urandom(500_000), "c.txt": b"hello"}


def test_get_torrent_file_downloads_matches_and_reruns_with_zero_pieces(tmp_path: Path, live) -> None:
    lv = live(FILES)
    torrent = tmp_path / "file.torrent"
    torrent.write_bytes(lv.swarm.raw)
    out = tmp_path / "dir"
    r = run(str(torrent), "-o", str(out), "--quiet")
    assert r.returncode == 0, r.stderr
    expected = [out.resolve() / "pack" / rel for rel in FILES]
    assert r.stdout.splitlines() == [str(p) for p in expected]
    for p, data in zip(expected, FILES.values(), strict=True):
        assert sha(p) == hashlib.sha256(data).hexdigest()
    served = lv.swarm.seeder.blocks_served
    assert served > 0

    again = run(str(torrent), "-o", str(out))  # not quiet: the notice goes to stderr
    assert again.returncode == 0, again.stderr
    assert again.stdout.splitlines() == [str(p) for p in expected]
    assert lv.swarm.seeder.blocks_served == served
    assert "all pieces already complete" in again.stderr


def test_hostile_paths_in_the_metainfo_stay_inside_the_output_folder(tmp_path: Path, live) -> None:
    files = {"1": b"one" * 9000, "2": b"two" * 9000}
    lv = live(files, name="../../evil", raw_paths={"1": [b"..", b"..", b"etc", b"x"], "2": [b"/abs", b"y"]})
    torrent = tmp_path / "evil.torrent"
    torrent.write_bytes(lv.swarm.raw)
    out = tmp_path / "work" / "dir"
    r = run(str(torrent), "-o", str(out), "--quiet")
    assert r.returncode == 0, r.stderr
    for line in r.stdout.splitlines():
        assert Path(line).is_relative_to(out.resolve())
    assert [p.name for p in (tmp_path / "work").iterdir()] == ["dir"]
    assert not (tmp_path / "evil").exists()


def test_ctrl_c_exits_130_and_the_rerun_resumes(tmp_path: Path, live) -> None:
    files = {"big.bin": os.urandom(2_000_000)}
    lv = live(files, delay=0.01)
    total_blocks = -(-2_000_000 // 16384)
    torrent = tmp_path / "big.torrent"
    torrent.write_bytes(lv.swarm.raw)
    out = tmp_path / "dir"

    p = anydm(str(torrent), "-o", str(out), "--quiet")
    deadline = time.monotonic() + 30
    while lv.swarm.seeder.blocks_served < 40 and time.monotonic() < deadline:
        time.sleep(0.02)
    p.send_signal(signal.SIGINT)
    _, err = p.communicate(timeout=30)
    assert p.returncode == 130, err
    assert "interrupted" in err
    served_before = lv.swarm.seeder.blocks_served
    assert served_before < total_blocks
    assert list(out.glob("*.anydm.json"))  # the resume record is kept

    lv.swarm.seeder.delay = 0.0
    r = run(str(torrent), "-o", str(out), "--quiet")
    assert r.returncode == 0, r.stderr
    assert sha(Path(r.stdout.splitlines()[0])) == hashlib.sha256(files["big.bin"]).hexdigest()
    fetched = lv.swarm.seeder.blocks_served - served_before
    assert fetched < total_blocks - 20  # resumed: well short of everything, whatever was in flight at SIGINT


def test_a_v2_only_magnet_exits_2(tmp_path: Path) -> None:
    r = run("magnet:?xt=urn:btmh:1220" + "ab" * 32, "--kind", "torrent", "-o", str(tmp_path), "--quiet")
    assert r.returncode == 2
    assert "v2" in r.stderr and "Traceback" not in r.stderr


def test_with_every_tracker_failing_it_keeps_waiting_and_never_prints_the_passkey_or_a_traceback(tmp_path: Path) -> None:
    from tests.engine.torrent.helpers import write_torrent

    torrent = tmp_path / "dead.torrent"
    write_torrent(
        torrent,
        {"a": b"abc" * 10000},
        tiers=[
            ["http://127.0.0.1:1/announce?passkey=SECRET"],
            ["udp://127.0.0.1:99999/announce?passkey=SECRET"],
            ["http://[bad/announce?passkey=SECRET"],
        ],
    )
    p = anydm(str(torrent), "-o", str(tmp_path / "dir"))
    time.sleep(3)
    assert p.poll() is None  # still waiting for peers, not hung up on and not crashed
    p.send_signal(signal.SIGINT)
    _, err = p.communicate(timeout=30)
    assert p.returncode == 130, err
    assert "tracker:" in err and "SECRET" not in err and "Traceback" not in err


def test_get_magnet_downloads_matches_and_the_rerun_skips_metadata_and_pieces(tmp_path: Path, live) -> None:
    lv = live(FILES)
    uri = lv.magnet()
    out = tmp_path / "dir"
    r = run(uri, "-o", str(out), "--quiet")
    assert r.returncode == 0, r.stderr
    expected = [out.resolve() / "pack" / rel for rel in FILES]
    assert r.stdout.splitlines() == [str(p) for p in expected]
    for p, data in zip(expected, FILES.values(), strict=True):
        assert sha(p) == hashlib.sha256(data).hexdigest()
    served, connections = lv.swarm.seeder.blocks_served, lv.swarm.seeder.connections
    assert served > 0 and connections >= 2  # one connection for the metadata, one or more for the pieces

    again = run(uri, "-o", str(out))
    assert again.returncode == 0, again.stderr
    assert again.stdout.splitlines() == [str(p) for p in expected]
    assert lv.swarm.seeder.blocks_served == served
    assert lv.swarm.seeder.connections == connections  # not even the metadata was fetched again
    assert "all pieces already complete" in again.stderr


def test_get_magnet_without_trackers_finds_the_peer_only_through_the_local_dht(tmp_path: Path, live) -> None:
    lv = live(FILES)
    env = lv.start_dht(hops=3)
    out = tmp_path / "dir"
    r = run(lv.magnet(tracker=False), "-o", str(out), "--quiet", env=env)
    assert r.returncode == 0, r.stderr
    assert lv.tracker.queries == []  # the tracker was never asked: the DHT did all of it
    for rel, data in FILES.items():
        assert sha(out / "pack" / rel) == hashlib.sha256(data).hexdigest()
    assert all(q != b"announce_peer" for n in lv.dht_nodes for q, _ in n.queries)


def test_get_torrent_file_without_trackers_uses_the_dht(tmp_path: Path, live) -> None:
    lv = live(FILES)
    env = lv.start_dht(hops=1)
    torrent = tmp_path / "n.torrent"
    torrent.write_bytes(make_torrent(FILES, announce=None)[0])  # the same info dictionary, so the same infohash, and no tracker
    assert lv.swarm.torrent.infohash == parse(torrent.read_bytes()).infohash
    r = run(str(torrent), "-o", str(tmp_path / "dir"), "--quiet", env=env)
    assert r.returncode == 0, r.stderr


def test_ctrl_c_during_the_metadata_phase_exits_130_within_a_second(tmp_path: Path) -> None:
    uri = "magnet:?xt=urn:btih:" + "ab" * 20 + "&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce%3Fpasskey%3DSECRET"
    p = anydm(uri, "-o", str(tmp_path / "dir"))
    time.sleep(2.5)
    assert p.poll() is None
    started = time.monotonic()
    p.send_signal(signal.SIGINT)
    _, err = p.communicate(timeout=10)
    assert p.returncode == 130, err
    assert time.monotonic() - started < 1.0
    assert "interrupted" in err and "Traceback" not in err and "SECRET" not in err
