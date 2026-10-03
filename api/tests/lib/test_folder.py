"""A folder named for what it holds: a torrent (#107) or a playlist (v0.5)."""

from pathlib import Path

from src.lib.folder import collection_dirname, named_folder
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


def test_a_collection_dir_is_its_title_and_the_sites_id() -> None:
    assert collection_dirname("29C3: Not my department", "PL1abc") == "29C3_ Not my department [PL1abc]"


def test_a_collection_dir_is_the_same_every_time() -> None:
    """Pure: it reads no disk, so nothing has to remember where a collection went."""
    assert collection_dirname("Talks", "PL1") == collection_dirname("Talks", "PL1")


def test_two_playlists_with_one_title_get_two_dirs() -> None:
    assert collection_dirname("Talks", "PL1") != collection_dirname("Talks", "PL2")


def test_a_collection_with_no_title_is_named_by_its_id() -> None:
    assert collection_dirname("", "UC123") == "UC123"
    assert collection_dirname(" . ", "UC123") == "UC123"


def test_a_title_or_id_with_separators_cannot_climb_out() -> None:
    name = collection_dirname("../../etc", "a/b\\c")
    assert "/" not in name and "\\" not in name
