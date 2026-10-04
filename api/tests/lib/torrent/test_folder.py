from pathlib import Path

from src.lib.torrent.folder import torrent_folder

HASH = "08ada5a7a6183aae1e09d831df6748d566095a10"


def test_a_torrent_gets_a_folder_named_after_it_and_its_hash(tmp_path: Path) -> None:
    assert torrent_folder(tmp_path, "Show.S01.1080p", HASH) == tmp_path / "Show.S01.1080p [08ada5a7]"


def test_a_name_cannot_leave_the_root_or_hide(tmp_path: Path) -> None:
    """A torrent's name is written by a stranger."""
    assert torrent_folder(tmp_path, "../../etc", HASH).name == "_.._etc [08ada5a7]"
    assert torrent_folder(tmp_path, "a/b\\c", HASH).name == "a_b_c [08ada5a7]"
    assert torrent_folder(tmp_path, "...", HASH) == tmp_path / HASH
    assert torrent_folder(tmp_path, ".hidden", HASH).name == "hidden [08ada5a7]"


def test_an_empty_name_falls_back_to_the_info_hash(tmp_path: Path) -> None:
    assert torrent_folder(tmp_path, "  ", HASH) == tmp_path / HASH


def test_a_long_name_is_capped(tmp_path: Path) -> None:
    name = torrent_folder(tmp_path, "x" * 400, HASH).name
    assert len(name) <= 150 and name.endswith("[08ada5a7]")


def test_the_same_torrent_names_the_same_folder_once_its_files_are_there(tmp_path: Path) -> None:
    """Pure: the folder is derived, never stored, so it must not move once written."""
    first = torrent_folder(tmp_path, "Sintel", HASH)
    (first).mkdir(parents=True)
    (first / "poster.jpg").write_bytes(b"someone else's")

    assert torrent_folder(tmp_path, "Sintel", HASH) == first


def test_two_releases_with_one_name_get_two_folders(tmp_path: Path) -> None:
    assert torrent_folder(tmp_path, "Sintel", HASH) != torrent_folder(tmp_path, "Sintel", "ff" * 20)
