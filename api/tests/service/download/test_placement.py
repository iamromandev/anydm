from pathlib import Path

from src.service.download.placement import free_path, move_dir, move_file_with_sidecars


def test_a_free_name_is_kept_and_a_taken_one_gets_the_tag(tmp_path: Path) -> None:
    assert free_path(tmp_path / "a.mp4", "1234abcd") == tmp_path / "a.mp4"
    (tmp_path / "a.mp4").write_bytes(b"x")
    assert free_path(tmp_path / "a.mp4", "1234abcd") == tmp_path / "a_1234abcd.mp4"
    (tmp_path / "Talks").mkdir()
    assert free_path(tmp_path / "Talks", "1234abcd") == tmp_path / "Talks_1234abcd"


def test_a_file_moves_with_its_subtitles_beside_it_and_in_a_subs_folder(tmp_path: Path) -> None:
    old, new = tmp_path / "old", tmp_path / "new"
    (old / "Subs").mkdir(parents=True)
    for name in ("Movie.mkv", "Movie.en.srt", "Subs/Movie.fr.srt", "Other.mkv", "Other.en.srt"):
        (old / name).write_bytes(b"x")

    moved = move_file_with_sidecars(old / "Movie.mkv", new, "1234abcd")

    assert moved == new / "Movie.mkv"
    assert sorted(p.relative_to(new).as_posix() for p in new.rglob("*") if p.is_file()) == [
        "Movie.en.srt",
        "Movie.mkv",
        "Subs/Movie.fr.srt",
    ]
    assert (old / "Other.en.srt").exists()


def test_a_clash_renames_the_file_and_its_subtitles_together(tmp_path: Path) -> None:
    old, new = tmp_path / "old", tmp_path / "new"
    old.mkdir()
    new.mkdir()
    (new / "Movie.mkv").write_bytes(b"taken")
    (old / "Movie.mkv").write_bytes(b"x")
    (old / "Movie.en.srt").write_bytes(b"x")

    moved = move_file_with_sidecars(old / "Movie.mkv", new, "1234abcd")

    assert moved == new / "Movie_1234abcd.mkv"
    assert (new / "Movie_1234abcd.en.srt").exists()


def test_a_directory_moves_whole_under_its_new_parent(tmp_path: Path) -> None:
    (tmp_path / "Talks [PL1]").mkdir()
    (tmp_path / "Talks [PL1]" / "01.mp4").write_bytes(b"x")
    moved = move_dir(tmp_path / "Talks [PL1]", tmp_path / "music", "1234abcd")
    assert moved == tmp_path / "music" / "Talks [PL1]"
    assert (moved / "01.mp4").exists()
