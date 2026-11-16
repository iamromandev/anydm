"""`anydm get --seed` end to end: real subprocesses. One seeds what it downloaded, another downloads from it."""

import asyncio
import hashlib
import os
import signal
import subprocess
import time
from pathlib import Path

import pytest

from tests.engine.torrent.cli_support import Live, anydm, run, sha
from tests.engine.torrent.seeding import stalled_peer

pytestmark = pytest.mark.torrent

FILES = {"a.bin": os.urandom(300_000), "sub/b.bin": os.urandom(400_000), "c.txt": b"hello"}


def wait_until(cond, timeout: float = 30.0) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if cond():
            return True
        time.sleep(0.05)
    return False


def seeding_port(lv: Live) -> int | None:
    """The port the seeding process announced: its `started` announce with nothing left to download."""
    for q in list(lv.tracker.queries):
        if q.get("event") == ["started"] and q.get("left") == ["0"]:
            return int(q["port"][0])
    return None


def stop(p: subprocess.Popen[str]) -> tuple[str, str]:
    if p.poll() is None:
        p.send_signal(signal.SIGINT)
    return p.communicate(timeout=30)


def test_with_seed_it_keeps_running_after_the_download_and_ctrl_c_then_exits_0_with_the_paths(tmp_path: Path, live) -> None:
    lv = live(FILES)
    torrent = tmp_path / "t.torrent"
    torrent.write_bytes(lv.swarm.raw)
    out = tmp_path / "a"
    p = anydm(str(torrent), "-o", str(out), "--seed", "--quiet")
    try:
        assert wait_until(lambda: seeding_port(lv) is not None), "never started seeding"
        assert sha(out / "pack" / "a.bin") == hashlib.sha256(FILES["a.bin"]).hexdigest()
        time.sleep(1.0)
        assert p.poll() is None  # the download is done and it has not exited
        p.send_signal(signal.SIGINT)
        started = time.monotonic()
        stdout, stderr = p.communicate(timeout=15)
        assert time.monotonic() - started < 1.0
        assert p.returncode == 0, stderr
        assert stdout.splitlines() == [str(out.resolve() / "pack" / rel) for rel in FILES]
        assert "Traceback" not in stderr
        assert any(q.get("event") == ["stopped"] for q in lv.tracker.queries)
    finally:
        stop(p)


def test_without_seed_it_exits_on_completion(tmp_path: Path, live) -> None:
    lv = live(FILES)
    torrent = tmp_path / "t.torrent"
    torrent.write_bytes(lv.swarm.raw)
    r = run(str(torrent), "-o", str(tmp_path / "a"), "--quiet")
    assert r.returncode == 0, r.stderr  # run() would time out if it kept seeding


def test_ctrl_c_during_the_download_with_seed_still_exits_130(tmp_path: Path, live) -> None:
    lv = live({"big.bin": os.urandom(2_000_000)}, delay=0.01)
    torrent = tmp_path / "t.torrent"
    torrent.write_bytes(lv.swarm.raw)
    p = anydm(str(torrent), "-o", str(tmp_path / "a"), "--seed", "--quiet")
    try:
        assert wait_until(lambda: lv.swarm.seeder.blocks_served >= 5)
        p.send_signal(signal.SIGINT)
        _, err = p.communicate(timeout=15)
        assert p.returncode == 130, err
    finally:
        stop(p)


def test_one_session_seeds_to_another_and_the_files_match(tmp_path: Path, live) -> None:
    lv = live(FILES)
    torrent = tmp_path / "t.torrent"
    torrent.write_bytes(lv.swarm.raw)
    first = anydm(str(torrent), "-o", str(tmp_path / "a"), "--seed", "--quiet")
    try:
        assert wait_until(lambda: seeding_port(lv) is not None), "never started seeding"
        port = seeding_port(lv)
        assert port is not None
        before = lv.swarm.seeder.blocks_served
        lv.tracker.peers = [("127.0.0.1", port)]  # from now on the tracker knows only the first session
        asyncio.run_coroutine_threadsafe(lv.swarm.seeder.stop(), lv.loop).result(10)  # nothing else can serve the second session
        r = run(str(torrent), "-o", str(tmp_path / "b"), "--quiet")
        assert r.returncode == 0, r.stderr
        assert lv.swarm.seeder.blocks_served == before
        for rel, data in FILES.items():
            assert sha(tmp_path / "b" / "pack" / rel) == hashlib.sha256(data).hexdigest()
    finally:
        stop(first)


def test_one_session_seeds_to_another_that_has_only_a_magnet_link(tmp_path: Path, live) -> None:
    lv = live(FILES)
    torrent = tmp_path / "t.torrent"
    torrent.write_bytes(lv.swarm.raw)
    first = anydm(str(torrent), "-o", str(tmp_path / "a"), "--seed", "--quiet")
    try:
        assert wait_until(lambda: seeding_port(lv) is not None), "never started seeding"
        port = seeding_port(lv)
        assert port is not None
        lv.tracker.peers = [("127.0.0.1", port)]
        asyncio.run_coroutine_threadsafe(lv.swarm.seeder.stop(), lv.loop).result(10)  # only the first session can answer now
        r = run(lv.magnet(), "-o", str(tmp_path / "b"), "--quiet")
        assert r.returncode == 0, r.stderr
        for rel, data in FILES.items():
            assert sha(tmp_path / "b" / "pack" / rel) == hashlib.sha256(data).hexdigest()
    finally:
        stop(first)


def test_ctrl_c_with_a_peer_that_never_reads_and_a_dead_tracker_still_exits_0_with_the_paths_within_seconds(
    tmp_path: Path, live
) -> None:
    lv = live(FILES)
    torrent = tmp_path / "t.torrent"
    torrent.write_bytes(lv.swarm.raw)
    out = tmp_path / "a"
    p = anydm(str(torrent), "-o", str(out), "--seed", "--quiet")
    try:
        assert wait_until(lambda: seeding_port(lv) is not None), "never started seeding"
        port = seeding_port(lv)
        assert port is not None
        _, w = asyncio.run_coroutine_threadsafe(stalled_peer(lv.swarm, port=port), lv.loop).result(10)
        lv.tracker.stall = 30.0  # the tracker stops answering: the `stopped` announce must not hold the exit
        p.send_signal(signal.SIGINT)
        started = time.monotonic()
        stdout, stderr = p.communicate(timeout=15)
        assert time.monotonic() - started < 1.5  # the capped `stopped` announce is 0.8 s of it
        assert p.returncode == 0, stderr
        assert stdout.splitlines() == [str(out.resolve() / "pack" / rel) for rel in FILES]
        assert "Traceback" not in stderr
        lv.loop.call_soon_threadsafe(w.close)
    finally:
        lv.tracker.stall = 0.0
        stop(p)
