from pathlib import Path

from src.lib.torrent.folder import stored_folder, torrent_folder

HASH = "08ada5a7a6183aae1e09d831df6748d566095a10"


def test_a_torrent_gets_a_folder_named_after_it(tmp_path: Path) -> None:
    assert torrent_folder(tmp_path, "Show.S01.1080p", HASH) == tmp_path / "Show.S01.1080p"


def test_a_name_cannot_leave_the_root_or_hide(tmp_path: Path) -> None:
    """A torrent's name is written by a stranger."""
    assert torrent_folder(tmp_path, "../../etc", HASH) == tmp_path / "_.._etc"
    assert torrent_folder(tmp_path, "a/b\\c", HASH) == tmp_path / "a_b_c"
    assert torrent_folder(tmp_path, "...", HASH) == tmp_path / HASH
    assert torrent_folder(tmp_path, ".hidden", HASH) == tmp_path / "hidden"
    assert torrent_folder(tmp_path, 'what?<>:"|*', HASH) == tmp_path / "what_______"


def test_an_empty_name_falls_back_to_the_info_hash(tmp_path: Path) -> None:
    assert torrent_folder(tmp_path, "  ", HASH) == tmp_path / HASH


def test_a_long_name_is_capped(tmp_path: Path) -> None:
    assert len(torrent_folder(tmp_path, "x" * 400, HASH).name) == 150


def test_a_folder_already_in_use_gets_the_hash_beside_it(tmp_path: Path) -> None:
    (tmp_path / "Sintel").mkdir()
    (tmp_path / "Sintel" / "poster.jpg").write_bytes(b"someone else's")

    assert torrent_folder(tmp_path, "Sintel", HASH) == tmp_path / "Sintel [08ada5a7]"


def test_an_empty_folder_is_reused(tmp_path: Path) -> None:
    """What an earlier torrent of that name left once its files were deleted."""
    (tmp_path / "Sintel").mkdir()

    assert torrent_folder(tmp_path, "Sintel", HASH) == tmp_path / "Sintel"


def test_a_stored_folder_that_exists_is_the_folder(tmp_path: Path) -> None:
    folder = tmp_path / "Sintel"
    folder.mkdir()

    assert stored_folder(str(folder), tmp_path, ["Sintel.mp4"]) == folder


def test_a_torrent_from_before_per_torrent_folders_is_read_from_the_root(tmp_path: Path) -> None:
    """Until #107 every torrent was written flat into the root, whatever its row said."""
    (tmp_path / "Sintel.mp4").write_bytes(b"data")

    assert stored_folder(str(tmp_path / "Sintel"), tmp_path, ["Sintel.mp4"]) == tmp_path


def test_a_new_torrent_with_nothing_on_disk_yet_keeps_its_folder(tmp_path: Path) -> None:
    assert stored_folder(str(tmp_path / "Sintel"), tmp_path, ["Sintel.mp4"]) == tmp_path / "Sintel"


def test_no_stored_folder_means_the_root(tmp_path: Path) -> None:
    assert stored_folder(None, tmp_path, []) == tmp_path
