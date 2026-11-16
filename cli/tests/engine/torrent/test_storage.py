import json
import os
import random
import shutil
import stat
import subprocess
import sys
import threading
from pathlib import Path

import pytest
from src.core.errors import DownloadError
from src.engine.torrent import storage as storage_mod
from src.engine.torrent.metainfo import parse
from src.engine.torrent.storage import RecheckCancelled, Storage, claim_layout

from tests.engine.torrent.helpers import make_torrent

FILES = {"a.bin": os.urandom(10000), "d/b.bin": os.urandom(30000), "c.bin": os.urandom(5)}
PLEN = 16384


@pytest.fixture
def parsed():
    raw, payload = make_torrent(FILES, piece_len=PLEN)
    return parse(raw), payload


def pieces_of(payload: bytes) -> list[bytes]:
    return [payload[i : i + PLEN] for i in range(0, len(payload), PLEN)]


def test_pieces_are_written_across_file_boundaries(tmp_path: Path, parsed) -> None:
    t, payload = parsed
    s = Storage(t, tmp_path)
    for i, data in enumerate(pieces_of(payload)):
        s.write_piece(i, data)
    s.close()
    for rel, data in FILES.items():
        assert (tmp_path / "pack" / rel).read_bytes() == data


def test_files_are_preallocated_to_their_length(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    Storage(t, tmp_path).close()
    assert (tmp_path / "pack" / "d" / "b.bin").stat().st_size == 30000
    assert (tmp_path / "pack" / "c.bin").stat().st_size == 5


def test_read_returns_the_bytes_spanning_files(tmp_path: Path, parsed) -> None:
    t, payload = parsed
    s = Storage(t, tmp_path)
    for i, data in enumerate(pieces_of(payload)):
        s.write_piece(i, data)
    assert s.read(1, 100, 5000) == pieces_of(payload)[1][100:5100]
    assert s.read(2, 0, 10_000) == pieces_of(payload)[2]  # clamped at the end of the torrent
    s.close()


def test_recheck_finds_only_intact_pieces(tmp_path: Path, parsed) -> None:
    t, payload = parsed
    s = Storage(t, tmp_path)
    ps = pieces_of(payload)
    s.write_piece(0, ps[0])
    s.write_piece(1, b"\x00" * len(ps[1]))  # wrong data
    s.write_piece(2, ps[2])
    assert s.recheck() == {0, 2}
    s.close()


def test_resume_record_is_trusted_when_files_are_unchanged(tmp_path: Path, parsed) -> None:
    t, payload = parsed
    s = Storage(t, tmp_path)
    s.write_piece(0, pieces_of(payload)[0])
    s.save({0})
    s.close()
    s = Storage(t, tmp_path)
    assert s.load() == {0}
    s.close()


def test_resume_record_with_a_modified_file_is_stale(tmp_path: Path, parsed) -> None:
    t, payload = parsed
    s = Storage(t, tmp_path)
    s.write_piece(0, pieces_of(payload)[0])
    s.save({0})
    s.close()
    with open(tmp_path / "pack" / "a.bin", "r+b") as f:
        f.write(b"zz")
    s = Storage(t, tmp_path)
    assert s.load() is None
    s.close()


def test_missing_or_corrupt_record_loads_as_none(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    s = Storage(t, tmp_path)
    assert s.load() is None
    s.record_path.write_text("{not json")
    assert s.load() is None
    s.record_path.write_text(json.dumps({"v": 99}))
    assert s.load() is None
    s.close()


def test_record_name_uses_the_infohash(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    s = Storage(t, tmp_path)
    assert s.record_path == tmp_path / f"{t.infohash.hex()}.anydm.json"
    s.close()


def test_symlink_escaping_dest_is_refused(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    outside = tmp_path / "outside"
    outside.mkdir()
    dest = tmp_path / "dest"
    dest.mkdir()
    (dest / "pack").symlink_to(outside)
    from src.engine.torrent.metainfo import MetainfoError

    with pytest.raises(MetainfoError):
        Storage(t, dest)
    assert list(outside.iterdir()) == []


def open_fds() -> int:
    return len(os.listdir("/dev/fd"))


def many_files(count: int) -> dict[str, bytes]:
    return {f"d{i % 7}/f{i}": bytes([i % 251]) * 10 for i in range(count)}


def test_open_descriptors_stay_bounded_however_many_files_there_are(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(storage_mod, "MAX_OPEN", 8)
    files = many_files(300)
    raw, payload = make_torrent(files, piece_len=PLEN)
    t = parse(raw)
    before = open_fds()
    s = Storage(t, tmp_path)
    assert open_fds() - before <= 8
    for i in range(t.piece_count):
        s.write_piece(i, payload[i * PLEN : (i + 1) * PLEN])
    assert open_fds() - before <= 8
    assert s.recheck() == set(range(t.piece_count))
    assert open_fds() - before <= 8
    s.close()
    assert open_fds() == before
    for rel, data in files.items():
        assert (tmp_path / "pack" / rel).read_bytes() == data


def test_a_3000_file_torrent_works_under_a_low_descriptor_limit(tmp_path: Path) -> None:
    script = f"""
import resource, sys
sys.path.insert(0, {str(Path(__file__).parents[3])!r})
resource.setrlimit(resource.RLIMIT_NOFILE, (120, 120))
from pathlib import Path
from src.engine.torrent.metainfo import parse
from src.engine.torrent.storage import Storage
from tests.engine.torrent.helpers import make_torrent
files = {{f"d{{i % 50}}/f{{i}}": bytes([i % 251]) * 10 for i in range(3000)}}
raw, payload = make_torrent(files, piece_len=16384)
t = parse(raw)
s = Storage(t, Path({str(tmp_path)!r}))
for i in range(t.piece_count):
    s.write_piece(i, payload[i * 16384 : (i + 1) * 16384])
assert s.recheck() == set(range(t.piece_count))
s.save(set(range(t.piece_count)))
assert s.load() == set(range(t.piece_count))
s.close()
print("ok")
"""
    r = subprocess.run([sys.executable, "-I", "-c", script], capture_output=True, text=True, timeout=120)
    assert r.stdout.strip() == "ok", r.stderr[-2000:]


def test_a_file_replaced_by_a_symlink_after_start_is_not_followed(tmp_path: Path, parsed, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(storage_mod, "MAX_OPEN", 1)
    t, payload = parsed
    s = Storage(t, tmp_path / "dest")
    outside = tmp_path / "outside.bin"
    outside.write_bytes(b"precious")
    s.write_piece(0, pieces_of(payload)[0])  # opens a.bin; the next file evicts it
    s.read(1, 0, 10)
    target = tmp_path / "dest" / "pack" / "a.bin"
    target.unlink()
    target.symlink_to(outside)
    with pytest.raises(OSError):
        s.write_piece(0, pieces_of(payload)[0])
    assert outside.read_bytes() == b"precious"
    s.close()


def test_a_directory_replaced_by_a_symlink_after_start_is_refused(tmp_path: Path, parsed, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(storage_mod, "MAX_OPEN", 1)
    t, payload = parsed
    s = Storage(t, tmp_path / "dest")
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "b.bin").write_bytes(b"precious")
    shutil.rmtree(tmp_path / "dest" / "pack" / "d")
    (tmp_path / "dest" / "pack" / "d").symlink_to(outside)
    with pytest.raises(OSError):
        s.write_piece(1, pieces_of(payload)[1])
    assert (outside / "b.bin").read_bytes() == b"precious"
    s.close()


def test_concurrent_saves_do_not_collide_and_leave_no_temporary_files(tmp_path: Path, parsed) -> None:
    t, payload = parsed
    s = Storage(t, tmp_path)
    s.write_piece(0, pieces_of(payload)[0])
    errors: list[BaseException] = []

    def hammer() -> None:
        try:
            for _ in range(40):
                s.save({0})
        except BaseException as e:
            errors.append(e)

    threads = [threading.Thread(target=hammer) for _ in range(8)]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    assert errors == []
    assert s.load() == {0}
    assert list(tmp_path.glob("*.tmp")) == []
    assert stat.S_IMODE(s.record_path.stat().st_mode) == 0o600
    s.close()


def test_save_after_close_writes_the_record_without_fsync(tmp_path: Path, parsed, monkeypatch: pytest.MonkeyPatch) -> None:
    t, payload = parsed
    s = Storage(t, tmp_path)
    s.write_piece(0, pieces_of(payload)[0])
    s.close()

    def boom(fd: int) -> None:
        raise AssertionError("fsync after close")

    monkeypatch.setattr(os, "fsync", boom)
    s.save({0})
    assert json.loads(s.record_path.read_text())["have"]


def test_not_enough_free_disk_space_fails_before_anything_is_created(tmp_path: Path, parsed, monkeypatch: pytest.MonkeyPatch) -> None:
    t, _ = parsed
    monkeypatch.setattr(shutil, "disk_usage", lambda p: shutil._ntuple_diskusage(100, 99, 1))
    with pytest.raises(DownloadError, match="disk space"):
        Storage(t, tmp_path)
    assert list(tmp_path.iterdir()) == []


def test_files_that_are_already_full_size_need_no_free_space(tmp_path: Path, parsed, monkeypatch: pytest.MonkeyPatch) -> None:
    t, _ = parsed
    Storage(t, tmp_path).close()
    monkeypatch.setattr(shutil, "disk_usage", lambda p: shutil._ntuple_diskusage(100, 100, 0))
    Storage(t, tmp_path).close()


def test_recheck_stops_when_cancelled(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    s = Storage(t, tmp_path)
    cancel = threading.Event()
    seen = []

    def progress(n: int) -> None:
        seen.append(n)
        cancel.set()

    with pytest.raises(RecheckCancelled):
        s.recheck(progress, cancel)
    assert seen == [1]
    s.close()


def test_a_resume_record_with_a_wrong_length_bitfield_is_rejected(tmp_path: Path, parsed) -> None:
    t, payload = parsed
    s = Storage(t, tmp_path)
    s.write_piece(0, pieces_of(payload)[0])
    s.save({0})
    raw = json.loads(s.record_path.read_text())
    raw["have"] += "00"
    s.record_path.write_text(json.dumps(raw))
    assert s.load() is None
    s.close()


def test_a_resume_record_with_spare_bits_set_is_rejected(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    s = Storage(t, tmp_path)
    s.save(set())
    raw = json.loads(s.record_path.read_text())
    raw["have"] = "ff"  # 3 pieces: the low 5 bits are padding
    s.record_path.write_text(json.dumps(raw))
    assert s.load() is None
    s.close()


def test_a_resume_record_whose_file_sizes_differ_is_rejected(tmp_path: Path, parsed) -> None:
    t, payload = parsed
    s = Storage(t, tmp_path)
    s.write_piece(0, pieces_of(payload)[0])
    s.save({0})
    raw = json.loads(s.record_path.read_text())
    raw["files"][0][0] += 1
    s.record_path.write_text(json.dumps(raw))
    assert s.load() is None
    s.close()


def test_zero_length_files_in_the_middle_are_created_and_skipped(tmp_path: Path) -> None:
    files = {"a": b"x" * 20000, "empty": b"", "b": b"y" * 20000}
    raw, payload = make_torrent(files, piece_len=PLEN)
    t = parse(raw)
    s = Storage(t, tmp_path)
    for i in range(t.piece_count):
        s.write_piece(i, payload[i * PLEN : (i + 1) * PLEN])
    assert s.recheck() == set(range(t.piece_count))
    s.close()
    assert (tmp_path / "pack" / "empty").read_bytes() == b""
    assert (tmp_path / "pack" / "b").read_bytes() == files["b"]


def test_pad_files_are_never_created_and_read_as_zeros(tmp_path: Path) -> None:
    files = {"a": b"x" * 20000, ".pad/12768": bytes(12768), "b": b"y" * 16384}
    raw, payload = make_torrent(files, piece_len=PLEN, attrs={".pad/12768": b"p"})
    t = parse(raw)
    s = Storage(t, tmp_path)
    for i in range(t.piece_count):
        s.write_piece(i, payload[i * PLEN : (i + 1) * PLEN])
    assert s.recheck() == set(range(t.piece_count))
    assert s.read(1, 0, PLEN)[3616:] == bytes(12768)[: PLEN - 3616]
    s.save({0, 1, 2})
    assert s.load() == {0, 1, 2}
    s.close()
    assert sorted(p.name for p in (tmp_path / "pack").iterdir()) == ["a", "b"]


def foreign(dest: Path, t, content: bytes = b"mine, not the torrent's") -> Path:
    p = dest / "pack" / "a.bin"
    p.parent.mkdir(parents=True)
    p.write_bytes(content)
    return p


def test_claim_layout_keeps_the_name_when_nothing_is_in_the_way(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    assert claim_layout(t, tmp_path) is t


def test_claim_layout_moves_to_a_new_top_level_name_instead_of_clobbering_a_foreign_file(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    mine = foreign(tmp_path, t, b"z" * 99_999)  # larger than the torrent's a.bin: truncating it would lose data
    claimed = claim_layout(t, tmp_path)
    assert claimed.files[0].path == ("pack (1)", "a.bin")
    assert claimed.files[1].path == ("pack (1)", "d", "b.bin")
    Storage(claimed, tmp_path).close()
    assert mine.read_bytes() == b"z" * 99_999


def test_claim_layout_skips_a_name_that_is_taken_too(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    foreign(tmp_path, t)
    (tmp_path / "pack (1)").mkdir()
    (tmp_path / "pack (1)" / "a.bin").write_bytes(b"also not ours")
    assert claim_layout(t, tmp_path).files[0].path[0] == "pack (2)"


def test_claim_layout_follows_the_resume_record(tmp_path: Path, parsed) -> None:
    t, payload = parsed
    foreign(tmp_path, t)
    claimed = claim_layout(t, tmp_path)
    s = Storage(claimed, tmp_path)
    s.write_piece(0, pieces_of(payload)[0])
    s.save({0})
    s.close()
    again = claim_layout(t, tmp_path)
    assert again.files[0].path[0] == "pack (1)"
    s = Storage(again, tmp_path)
    assert s.load() == {0}
    s.close()


def test_claim_layout_accepts_existing_files_that_hold_this_torrents_data(tmp_path: Path, parsed) -> None:
    t, payload = parsed
    s = Storage(t, tmp_path)
    s.write_piece(0, pieces_of(payload)[0])  # a partial copy, and no record
    s.close()
    assert claim_layout(t, tmp_path) is t


def test_claim_layout_treats_a_file_where_the_directory_belongs_as_foreign(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    (tmp_path / "pack").write_text("a plain file")
    assert claim_layout(t, tmp_path).files[0].path[0] == "pack (1)"


def test_claim_layout_for_a_single_file_torrent_renames_the_file(tmp_path: Path) -> None:
    raw, _ = make_torrent({"f": b"abc" * 100}, name="x.bin", single=True)
    t = parse(raw)
    (tmp_path / "x.bin").write_bytes(b"mine")
    assert claim_layout(t, tmp_path).files[0].path == ("x.bin (1)",)


def test_claim_layout_ignores_a_hostile_root_in_the_record(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    (tmp_path / f"{t.infohash.hex()}.anydm.json").write_text(json.dumps({"root": "../../etc"}))
    assert claim_layout(t, tmp_path) is t


def test_claim_layout_can_be_cancelled(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    foreign(tmp_path, t)
    cancel = threading.Event()
    cancel.set()
    with pytest.raises(RecheckCancelled):
        claim_layout(t, tmp_path, cancel)


def test_a_record_that_only_remembers_the_folder_is_not_trusted_as_a_bitfield(tmp_path: Path, parsed) -> None:
    t, _ = parsed
    s = Storage(t, tmp_path)
    s.save(set(), checked=False)
    assert s.load() is None
    s.save(set())
    assert s.load() == set()
    s.close()


def test_reads_from_many_threads_at_once_return_the_right_bytes_with_few_descriptors(
    tmp_path: Path, parsed, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The seeder reads in worker threads: the descriptor cache must survive eviction under concurrent reads."""
    t, payload = parsed
    monkeypatch.setattr(storage_mod, "MAX_OPEN", 1)  # every read of another file closes and reopens
    s = Storage(t, tmp_path)
    for i, data in enumerate(pieces_of(payload)):
        s.write_piece(i, data)
    problems: list[str] = []

    def reader(seed: int) -> None:
        rnd = random.Random(seed)
        for _ in range(300):
            off = rnd.randrange(len(payload) - 100)
            n = rnd.randrange(1, 100)
            index, begin = divmod(off, PLEN)
            n = min(n, t.piece_size(index) - begin)
            if s.read(index, begin, n) != payload[off : off + n]:
                problems.append(f"bad read at {off}")

    threads = [threading.Thread(target=reader, args=(i,)) for i in range(8)]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    s.close()
    assert problems == []
