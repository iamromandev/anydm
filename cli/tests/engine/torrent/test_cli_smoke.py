"""The quick CLI end to end checks for torrents. They run in the default `make test` set, a few seconds each."""

import hashlib
import os
import signal
import time
from pathlib import Path

from tests.engine.torrent.cli_support import anydm, run, sha
from tests.engine.torrent.trackers import RawTcpServer

SMALL = {"a.bin": os.urandom(60_000), "sub/b.bin": os.urandom(40_000)}


def test_get_torrent_downloads_prints_the_paths_and_exits_zero(tmp_path: Path, live) -> None:
    lv = live(SMALL)
    torrent = tmp_path / "small.torrent"
    torrent.write_bytes(lv.swarm.raw)
    out = tmp_path / "dir"
    r = run(str(torrent), "-o", str(out), "--quiet")
    assert r.returncode == 0, r.stderr
    expected = [out.resolve() / "pack" / rel for rel in SMALL]
    assert r.stdout.splitlines() == [str(p) for p in expected]
    for p, data in zip(expected, SMALL.values(), strict=True):
        assert sha(p) == hashlib.sha256(data).hexdigest()


def test_get_magnet_with_a_tracker_downloads_prints_the_paths_and_exits_zero(tmp_path: Path, live) -> None:
    lv = live(SMALL)
    out = tmp_path / "dir"
    r = run(lv.magnet(), "-o", str(out), "--quiet")
    assert r.returncode == 0, r.stderr
    expected = [out.resolve() / "pack" / rel for rel in SMALL]
    assert r.stdout.splitlines() == [str(p) for p in expected]
    for p, data in zip(expected, SMALL.values(), strict=True):
        assert sha(p) == hashlib.sha256(data).hexdigest()
    assert (out / f"{lv.swarm.torrent.infohash.hex()}.torrent").is_file()  # the metadata is kept for a rerun


def test_ctrl_c_during_a_torrent_download_exits_130_and_keeps_the_record(tmp_path: Path, live) -> None:
    files = {"big.bin": os.urandom(800_000)}
    lv = live(files, delay=0.05)  # about 2.5 s for everything
    torrent = tmp_path / "big.torrent"
    torrent.write_bytes(lv.swarm.raw)
    out = tmp_path / "dir"
    p = anydm(str(torrent), "-o", str(out), "--quiet")
    deadline = time.monotonic() + 30
    while lv.swarm.seeder.blocks_served < 3 and time.monotonic() < deadline:
        time.sleep(0.02)
    started = time.monotonic()
    p.send_signal(signal.SIGINT)
    _, err = p.communicate(timeout=30)
    assert p.returncode == 130, err
    assert "interrupted" in err and "Traceback" not in err
    assert time.monotonic() - started < 5
    assert list(out.glob("*.anydm.json"))


def test_trackers_that_break_in_odd_ways_do_not_stop_the_download_or_leak_the_passkey(tmp_path: Path, live) -> None:
    garbage = RawTcpServer(b"garbage\r\n\r\n").start()
    try:
        lv = live(
            SMALL,
            extra_tiers=[
                [garbage.url],
                ["udp://127.0.0.1:99999/announce?passkey=SECRET"],
                ["http://[bad/announce?passkey=SECRET"],
            ],
        )
        torrent = tmp_path / "t.torrent"
        torrent.write_bytes(lv.swarm.raw)
        r = run(str(torrent), "-o", str(tmp_path / "dir"))  # not quiet: notices reach stderr
        assert r.returncode == 0, r.stderr
        assert len(r.stdout.splitlines()) == 2
        assert "SECRET" not in r.stderr and "Traceback" not in r.stderr
    finally:
        garbage.stop()
