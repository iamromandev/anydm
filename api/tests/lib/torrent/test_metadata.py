"""``piece_specs`` — a .torrent's info dict as one row per piece.

The torrent bytes come as the user pasted them, or from the engine after a
magnet's metadata arrives. Either way the interesting parts — piece length,
the concatenated SHA-1s, the total size — sit inside the bencoded info dict,
and every one of them is easy to read wrongly by hand.
"""

from __future__ import annotations

import hashlib
from typing import Any

import pytest
from src.core.error import Error
from src.lib.torrent.metadata import piece_specs


def bencode(value: Any) -> bytes:
    """Just enough bencode to speak .torrent: dict, list, int, bytes, str."""
    if isinstance(value, dict):
        body = b"".join(bencode(key) + bencode(item) for key, item in sorted(value.items()))
        return b"d" + body + b"e"
    if isinstance(value, list):
        return b"l" + b"".join(bencode(item) for item in value) + b"e"
    if isinstance(value, int):
        return f"i{value}e".encode()
    if isinstance(value, str):
        value = value.encode()
    return f"{len(value)}:".encode() + value


def torrent_bytes(*, piece_length: int, files: list[tuple[str, int]], name: str = "Release") -> bytes:
    info: dict[bytes, Any] = {
        b"name": name,
        b"piece length": piece_length,
        b"pieces": b"".join(
            b"\x01" * 20 for _ in range(-(-sum(size for _, size in files) // max(piece_length, 1)))
        ),
    }
    info[b"files" if len(files) > 1 else b"length"] = (
        [{b"path": [path], b"length": size} for path, size in files] if len(files) > 1 else files[0][1]
    )
    return bencode({b"info": info, b"announce": "http://tracker.test/announce"})


class TestPieceSpecs:
    def test_one_row_per_piece_with_index_size_and_hash(self) -> None:
        specs = piece_specs(torrent_bytes(piece_length=512, files=[("a.mkv", 600)]))

        assert [spec.index for spec in specs] == [0, 1]
        assert [spec.size for spec in specs] == [512, 88]
        assert specs[0].hash == "01" * 20
        assert specs[1].hash == "01" * 20

    def test_the_last_piece_holds_the_remainder(self) -> None:
        specs = piece_specs(torrent_bytes(piece_length=1000, files=[("exact.bin", 3000)]))

        assert [spec.size for spec in specs] == [1000, 1000, 1000]

    def test_a_multi_file_torrent_sizes_from_every_file(self) -> None:
        specs = piece_specs(torrent_bytes(piece_length=100, files=[("a.mkv", 150), ("b.srt", 100)]))

        assert [spec.size for spec in specs] == [100, 100, 50]

    def test_hashes_come_straight_from_the_info_dict(self) -> None:
        blob = b"".join(hashlib.sha1(str(index).encode()).digest() for index in range(2))
        data = b"d4:infod6:lengthi300e12:piece lengthi150e6:pieces" + str(len(blob)).encode() + b":" + blob + b"ee"

        specs = piece_specs(data)

        assert [spec.hash for spec in specs] == [
            hashlib.sha1(b"0").hexdigest(),
            hashlib.sha1(b"1").hexdigest(),
        ]

    def test_a_torrent_with_no_data_has_no_pieces(self) -> None:
        assert piece_specs(torrent_bytes(piece_length=100, files=[("empty.bin", 0)])) == []


class TestRejections:
    def test_not_a_torrent_at_all(self) -> None:
        with pytest.raises(Error, match="Invalid torrent"):
            piece_specs(b"i9e")

    def test_truncated_bytes_are_rejected_not_crash(self) -> None:
        for blob in (b"i12", b"4x:abc", b"12abc", b"d4:infod6:lengthi12"):
            with pytest.raises(Error, match="Invalid torrent"):
                piece_specs(blob)

    def test_an_info_dict_without_pieces(self) -> None:
        with pytest.raises(Error, match="piece hashes"):
            piece_specs(bencode({b"info": {b"name": "x", b"piece length": 100, b"length": 100}}))

    def test_a_pieces_blob_that_is_not_whole_hashes(self) -> None:
        with pytest.raises(Error, match="piece hashes"):
            piece_specs(b"d4:infod6:lengthi300e12:piece lengthi150e6:pieces25:" + b"\x01" * 25 + b"ee")
