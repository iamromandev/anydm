"""``match_category``: which category a file's name or Content-Type says, and when it says none."""

import uuid

from src.data.repo.category import CategoryRow
from src.service.category.match import match_category


def a_category(
    slug: str, position: int, extensions: tuple[str, ...] = (), mime_prefixes: tuple[str, ...] = ()
) -> CategoryRow:
    return CategoryRow(uuid.uuid4(), slug.title(), slug, slug, position, False, extensions, mime_prefixes)


VIDEOS = a_category("videos", 1, (".mkv", ".mp4"), ("video/",))
MUSIC = a_category("music", 2, (".mp3",), ("audio/",))
SOFTWARE = a_category("software", 3, (".iso",))
ALL = [VIDEOS, MUSIC, SOFTWARE]


def test_an_extension_picks_its_category() -> None:
    assert match_category(ALL, "disc.iso") is SOFTWARE
    assert match_category(ALL, "film.MKV") is VIDEOS


def test_the_last_extension_counts_in_a_double_one() -> None:
    assert match_category([a_category("archives", 1, (".gz",))], "backup.tar.gz") is not None


def test_an_unknown_or_missing_extension_matches_nothing() -> None:
    assert match_category(ALL, "notes.xyz") is None
    assert match_category(ALL, "download") is None
    assert match_category(ALL, None) is None
    assert match_category([], "disc.iso") is None


def test_the_content_type_is_tried_when_the_name_says_nothing() -> None:
    assert match_category(ALL, "download", "video/mp4") is VIDEOS
    assert match_category(ALL, "download", "Audio/MPEG; charset=binary") is MUSIC
    assert match_category(ALL, "download", "application/octet-stream") is None


def test_an_extension_beats_a_content_type_that_disagrees() -> None:
    assert match_category(ALL, "song.mp3", "video/mp4") is MUSIC


def test_a_tie_goes_to_the_category_placed_first_whatever_order_the_rows_came_in() -> None:
    late = a_category("late", 9, (".mkv",))
    assert match_category([late, VIDEOS], "a.mkv") is VIDEOS
    assert match_category([VIDEOS, late], "a.mkv") is VIDEOS
