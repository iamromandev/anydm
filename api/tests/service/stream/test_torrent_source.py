from src.lib.torrent.protocol import FileInfo
from src.service.stream.torrent_source import pick_media_file


def test_picks_the_largest_media_file() -> None:
    files = [
        FileInfo(index=0, path="Movie.en.srt", size_bytes=5_000),
        FileInfo(index=1, path="Movie.mkv", size_bytes=900_000_000),
        FileInfo(index=2, path="sample.mp4", size_bytes=10_000_000),
    ]
    picked = pick_media_file(files)
    assert picked is not None
    assert picked.index == 1


def test_returns_none_when_nothing_matches() -> None:
    files = [
        FileInfo(index=0, path="readme.txt", size_bytes=100),
        FileInfo(index=1, path="cover.jpg", size_bytes=50_000),
    ]
    assert pick_media_file(files) is None


def test_returns_none_for_an_empty_list() -> None:
    assert pick_media_file([]) is None


def test_extension_match_is_case_insensitive() -> None:
    files = [FileInfo(index=0, path="Movie.MKV", size_bytes=100)]
    picked = pick_media_file(files)
    assert picked is not None
    assert picked.index == 0


def test_ignores_files_with_a_media_looking_name_but_wrong_extension() -> None:
    files = [FileInfo(index=0, path="Movie.mkv.part", size_bytes=900_000_000)]
    assert pick_media_file(files) is None
