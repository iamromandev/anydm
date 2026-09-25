import pytest
from src.lib.media.audio import language_in_name
from src.lib.media.sidecar import Sidecar, decode_subtitles, match_sidecars


def _paths(found: list[Sidecar]) -> list[str]:
    return [sidecar.path for sidecar in found]


def test_93s_layout_matches_both_files() -> None:
    """``Movie.en.srt`` beside the film, and ``Subs/2_English.srt`` below it."""
    paths = ["Movie.mkv", "Movie.en.srt", "Subs/2_English.srt"]

    found = match_sidecars("Movie.mkv", paths)

    assert found == [
        Sidecar("Movie.en.srt", "en", "Movie.en.srt"),
        Sidecar("Subs/2_English.srt", "en", "2_English.srt"),
    ]


def test_a_release_folder_inside_the_torrent_works_the_same() -> None:
    paths = ["Movie (2020)/Movie (2020).mkv", "Movie (2020)/Subs/3_French.srt", "Movie (2020)/sample.txt"]
    assert _paths(match_sidecars("Movie (2020)/Movie (2020).mkv", paths)) == ["Movie (2020)/Subs/3_French.srt"]


def test_a_season_pack_matches_each_episode_s_own() -> None:
    paths = [
        "Show.S01E01.mkv",
        "Show.S01E02.mkv",
        "Show.S01E01.en.srt",
        "Show.S01E02.en.srt",
        "Subs/Show.S01E01/2_English.srt",
        "Subs/Show.S01E02/2_English.srt",
        "Subs/Show.S01E02/3_Spanish.srt",
        # Loose in Subs/ with two videos here: whose is it? Nobody's.
        "Subs/English.srt",
    ]

    assert _paths(match_sidecars("Show.S01E02.mkv", paths)) == [
        "Show.S01E02.en.srt",
        "Subs/Show.S01E02/2_English.srt",
        "Subs/Show.S01E02/3_Spanish.srt",
    ]


def test_a_longer_name_that_also_fits_wins_the_file() -> None:
    paths = ["Movie.mkv", "Movie 2.mkv", "Movie.en.srt", "Movie 2.en.srt"]

    assert _paths(match_sidecars("Movie.mkv", paths)) == ["Movie.en.srt"]
    assert _paths(match_sidecars("Movie 2.mkv", paths)) == ["Movie 2.en.srt"]


def test_another_film_s_subtitles_and_other_folders_are_left_alone() -> None:
    paths = [
        "Movie.mkv",
        "Other.en.srt",
        "Extras/Movie.en.srt",
        "Subs/Extras/Movie.en.srt",
        "Movie.en.txt",
        "Movie.nfo",
    ]
    assert match_sidecars("Movie.mkv", paths) == []


def test_the_name_matches_whatever_its_case() -> None:
    paths = ["movie.MKV", "MOVIE.EN.SRT", "SUBS/english.ass"]
    assert _paths(match_sidecars("movie.MKV", paths)) == ["MOVIE.EN.SRT", "SUBS/english.ass"]


@pytest.mark.parametrize(
    ("name", "language", "forced", "hearing"),
    [
        ("Movie.en.srt", "en", False, False),
        ("Movie.eng.srt", "en", False, False),
        ("Movie.English.srt", "en", False, False),
        ("Movie.fre.forced.srt", "fr", True, False),
        ("Movie.en.hi.srt", "en", False, True),
        ("Movie.en.SDH.srt", "en", False, True),
        ("Movie.pt-BR.srt", "pt", False, False),
        ("Movie.srt", None, False, False),
        ("Movie.1080p.srt", None, False, False),
    ],
)
def test_the_language_and_kind_come_from_the_name(name: str, language: str | None, forced: bool, hearing: bool) -> None:
    (found,) = match_sidecars("Movie.mkv", ["Movie.mkv", name])
    assert (found.language, found.forced, found.hearing_impaired) == (language, forced, hearing)


def test_a_language_name_or_code_is_a_language_and_other_words_aren_t() -> None:
    assert [language_in_name(word) for word in ("English", "ger", "es", "Latino", "hi", "hindi", "1080p", "x")] == [
        "en", "de", "es", "es", None, "hi", None, None,
    ]


def test_subtitles_are_decoded_as_utf8_else_windows_1252() -> None:
    assert decode_subtitles("Café\n".encode()) == "Café\n"
    assert decode_subtitles(b"\xef\xbb\xbfCaf\xc3\xa9") == "Café"
    assert decode_subtitles("Café, señor".encode("cp1252")) == "Café, señor"
