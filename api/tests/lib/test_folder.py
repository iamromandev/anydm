"""A folder named for what it holds: a torrent (#107) or a playlist (v0.5)."""

from pathlib import Path

from src.lib.folder import named_folder
from src.lib.site.filename import number_prefix


def test_a_name_becomes_a_safe_folder(tmp_path: Path) -> None:
    assert named_folder(tmp_path, 'A/B: "list"?', "PL1") == tmp_path / "A_B_ _list__"


def test_a_folder_already_holding_files_is_left_alone(tmp_path: Path) -> None:
    (tmp_path / "List").mkdir()
    (tmp_path / "List" / "other.mp4").write_bytes(b"x")

    assert named_folder(tmp_path, "List", "PLabcdefgh") == tmp_path / "List [PLabcdef]"


def test_an_empty_folder_is_reused(tmp_path: Path) -> None:
    (tmp_path / "List").mkdir()

    assert named_folder(tmp_path, "List", "PL1") == tmp_path / "List"


def test_an_empty_name_falls_back(tmp_path: Path) -> None:
    assert named_folder(tmp_path, " .. ", "PL1") == tmp_path / "PL1"


def test_numbers_pad_to_the_largest_and_at_least_two() -> None:
    assert number_prefix(7, 9) == "07_"
    assert number_prefix(7, 120) == "007_"
    assert number_prefix(1234, 1234) == "1234_"
