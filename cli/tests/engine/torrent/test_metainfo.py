import hashlib
from pathlib import Path
from typing import Any

import pytest
from src.engine.torrent.bencode import decode_top
from src.engine.torrent.metainfo import MetainfoError, parse

from tests.engine.torrent.helpers import info_bytes, make_torrent


def test_multi_file_torrent_is_parsed() -> None:
    raw, payload = make_torrent({"a.bin": b"x" * 20000, "sub/b.bin": b"y" * 100}, piece_len=16384)
    t = parse(raw)
    assert t.name == "pack"
    assert [(f.path, f.length) for f in t.files] == [(("pack", "a.bin"), 20000), (("pack", "sub", "b.bin"), 100)]
    assert t.total == len(payload) == 20100
    assert t.piece_count == 2
    assert t.piece_size(1) == 20100 - 16384


def test_single_file_torrent_has_no_directory() -> None:
    raw, _ = make_torrent({"f": b"abc"}, name="one.txt", single=True)
    assert [f.path for f in parse(raw).files] == [("one.txt",)]


def test_infohash_is_sha1_of_the_original_info_bytes() -> None:
    raw, _ = make_torrent({"a": b"abc"})
    _, spans = decode_top(raw)
    s, e = spans[b"info"]
    assert parse(raw).infohash == hashlib.sha1(raw[s:e]).digest()


def test_the_torrent_keeps_the_info_bytes_as_they_were_hashed_even_when_renamed() -> None:
    raw, _ = make_torrent({"a": b"abc"})
    t = parse(raw)
    assert t.info == info_bytes(raw)
    assert hashlib.sha1(t.info).digest() == t.infohash
    assert t.with_root("pack (1)").info == t.info  # a new folder name on disk does not change what the swarm knows


def test_announce_tiers_use_announce_list_then_announce() -> None:
    raw, _ = make_torrent({"a": b"abc"}, announce="http://a/x", tiers=[["http://b/x", "udp://c:1"], ["http://d/x"]])
    assert parse(raw).tiers == [["http://b/x", "udp://c:1"], ["http://d/x"]]
    raw, _ = make_torrent({"a": b"abc"}, announce="http://a/x")
    assert parse(raw).tiers == [["http://a/x"]]


def test_hostile_paths_are_cleaned_and_stay_inside_dest(tmp_path: Path) -> None:
    raw, _ = make_torrent(
        {"1": b"a", "2": b"b", "3": b"c", "4": b"d"},
        name="../../evil",
        raw_paths={
            "1": [b"..", b"..", b"etc", b"x"],
            "2": [b"/abs", b"y"],
            "3": [b"a/../../z"],
            "4": [b"", b"."],
        },
    )
    t = parse(raw)
    paths = t.file_paths(tmp_path)
    assert len(set(paths)) == 4
    root = tmp_path.resolve()
    for p in paths:
        assert p.resolve().is_relative_to(root)
        assert p.parent != root or p.name  # a file, never dest itself


def test_cleaned_name_collisions_get_distinct_paths(tmp_path: Path) -> None:
    raw, _ = make_torrent({"1": b"a", "2": b"b"}, raw_paths={"1": [b"x"], "2": [b"/x"]})
    assert len(set(parse(raw).file_paths(tmp_path))) == 2


@pytest.mark.parametrize(
    "mutate",
    [
        lambda m: m.pop(b"info"),
        lambda m: m[b"info"].pop(b"pieces"),
        lambda m: m[b"info"].update({b"pieces": b"short"}),
        lambda m: m[b"info"].update({b"piece length": 0}),
        lambda m: m[b"info"].update({b"meta version": 2}),
        lambda m: m[b"info"][b"files"][0].update({b"length": -1}),
        lambda m: m[b"info"][b"files"][0].update({b"path": []}),
    ],
)
def test_invalid_metainfo_is_rejected(mutate) -> None:
    from src.engine.torrent.bencode import decode, encode

    raw, _ = make_torrent({"a": b"abc"})
    m = decode(raw)
    mutate(m)
    with pytest.raises(MetainfoError):
        parse(encode(m))


def test_not_bencode_is_rejected() -> None:
    with pytest.raises(MetainfoError):
        parse(b"<html>")


def test_overlong_integer_in_a_torrent_is_a_metainfo_error() -> None:
    raw, _ = make_torrent({"a": b"abc"})
    with pytest.raises(MetainfoError):
        parse(raw.replace(b"i16384e", b"i" + b"9" * 5000 + b"e"))


def test_overlong_string_length_in_a_torrent_is_a_metainfo_error() -> None:
    raw, _ = make_torrent({"a": b"abc"})
    with pytest.raises(MetainfoError):
        parse(raw.replace(b"4:pack", b"9" * 5000 + b":pack"))


def test_piece_length_over_32_mib_is_rejected() -> None:
    raw, _ = make_torrent({"a": b"abc"}, piece_len=(32 << 20) + 1)
    with pytest.raises(MetainfoError, match="piece length"):
        parse(raw)
    raw, _ = make_torrent({"a": b"abc"}, piece_len=32 << 20)
    assert parse(raw).piece_length == 32 << 20


def test_pieces_count_that_does_not_match_the_total_is_rejected() -> None:
    from src.engine.torrent.bencode import decode, encode

    raw, _ = make_torrent({"a": b"x" * 40000}, piece_len=16384)  # 3 pieces
    m: Any = decode(raw)
    m[b"info"][b"pieces"] = m[b"info"][b"pieces"][:40]  # claims 2
    with pytest.raises(MetainfoError, match="piece count"):
        parse(encode(m))


def test_zero_length_file_in_the_middle_keeps_offsets_right() -> None:
    raw, payload = make_torrent({"a": b"x" * 100, "empty": b"", "b": b"y" * 50})
    t = parse(raw)
    assert [(f.length, f.offset) for f in t.files] == [(100, 0), (0, 100), (50, 100)]
    assert t.total == len(payload) == 150


def test_names_differing_only_in_case_become_distinct() -> None:
    raw, _ = make_torrent({"Foo.txt": b"a", "foo.txt": b"b", "FOO.TXT": b"c"})
    names = [f.path[-1] for f in parse(raw).files]
    assert names == ["Foo.txt", "foo.txt (1)", "FOO.TXT (2)"]


def test_names_differing_only_in_unicode_normalization_become_distinct() -> None:
    raw, _ = make_torrent({"caf\u00e9": b"a", "cafe\u0301": b"b"})
    a, b = (f.path[-1] for f in parse(raw).files)
    assert a != b and a.startswith("caf\u00e9") and b.endswith(" (1)")


def test_a_file_and_a_directory_with_the_same_name_are_renamed_apart(tmp_path: Path) -> None:
    from src.engine.torrent.storage import Storage

    for files in ({"a": b"1", "a/b": b"2", "a/c": b"3"}, {"a/b": b"2", "a": b"1", "A/c": b"3"}):
        raw, _ = make_torrent(files)
        t = parse(raw)
        paths = [f.path for f in t.files]
        assert len(set(paths)) == len(paths)
        for p in paths:  # nothing is both a file and a directory
            assert not any(q[: len(p)] == p and q != p for q in paths)
        dest = tmp_path / str(len(files)) / str(sorted(files))[:5].replace("/", "_")
        dest.mkdir(parents=True)
        Storage(t, dest).close()  # the layout can really be created


def test_a_renamed_directory_keeps_all_its_files_together() -> None:
    raw, _ = make_torrent({"a": b"1", "a/b": b"2", "a/c": b"3"})
    p = [f.path for f in parse(raw).files]
    assert p[1][:2] == p[2][:2]  # same renamed directory for b and c


def test_v2_and_hybrid_torrents_are_refused_by_name() -> None:
    from src.engine.torrent.bencode import decode, encode

    raw, _ = make_torrent({"a": b"abc"})
    m: Any = decode(raw)
    m[b"info"][b"file tree"] = {}
    with pytest.raises(MetainfoError, match="hybrid"):
        parse(encode(m))


def test_pad_files_are_kept_out_of_the_paths_but_counted_in_the_payload(tmp_path: Path) -> None:
    files = {"a": b"x" * 20000, ".pad/12768": bytes(12768), "b": b"y" * 16384}
    raw, payload = make_torrent(files, attrs={".pad/12768": b"p"})
    t = parse(raw)
    assert len(t.files) == 3 and [f.pad for f in t.files] == [False, True, False]
    assert t.total == len(payload) and t.piece_count == 3
    assert [p.name for p in t.file_paths(tmp_path)] == ["a", "b"]


def test_tracker_lists_are_bounded() -> None:
    urls = [f"http://t{i}.example/announce" for i in range(500)]
    raw, _ = make_torrent({"a": b"abc"}, tiers=[urls[i : i + 5] for i in range(0, 500, 5)])
    tiers = parse(raw).tiers
    assert sum(len(t) for t in tiers) <= 64


def test_private_flag_is_read_from_the_info_dictionary() -> None:
    plain, _ = make_torrent({"f": b"abc"})
    private, _ = make_torrent({"f": b"abc"}, private=True)
    assert parse(plain).private is False
    assert parse(private).private is True
