"""A folder named for what it holds: a playlist (v0.5)."""

from src.lib.folder import collection_dirname
from src.lib.site.filename import number_prefix


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
