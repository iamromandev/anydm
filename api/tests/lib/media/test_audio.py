import pytest
from src.lib.media.audio import AudioTrack, language_key, pick_audio_track


@pytest.mark.parametrize(
    ("code", "key"),
    [
        ("eng", "en"),
        ("en", "en"),
        ("en-US", "en"),
        ("EN_gb", "en"),
        ("fre", "fr"),
        ("fra", "fr"),
        ("ger", "de"),
        ("chi", "zh"),
        ("zh-Hans", "zh"),
        ("nob", "no"),
        # Not in the table: still compared as itself.
        ("kat", "kat"),
        ("und", None),
        ("", None),
        (None, None),
    ],
)
def test_language_key_folds_file_and_site_codes_together(code: str | None, key: str | None) -> None:
    assert language_key(code) == key


DUB_AND_ORIGINAL = [
    AudioTrack(0, language="spa", channels=6, default=True),
    AudioTrack(1, language="eng", channels=2),
    AudioTrack(2, language="fre", channels=2),
]


def test_the_preferred_language_comes_first() -> None:
    assert pick_audio_track(DUB_AND_ORIGINAL, "en") == 1
    assert pick_audio_track(DUB_AND_ORIGINAL, "fra") == 2


def test_the_marked_track_comes_next() -> None:
    assert pick_audio_track(DUB_AND_ORIGINAL, "de") == 0
    assert pick_audio_track(DUB_AND_ORIGINAL) == 0


def test_the_first_track_comes_last() -> None:
    tracks = [AudioTrack(0, language="jpn"), AudioTrack(1, language="eng")]
    assert pick_audio_track(tracks, "ko") == 0


def test_a_named_track_wins_when_the_source_has_it() -> None:
    assert pick_audio_track(DUB_AND_ORIGINAL, "en", wanted=2) == 2
    assert pick_audio_track(DUB_AND_ORIGINAL, "en", wanted=9) == 1


def test_no_tracks_picks_nothing() -> None:
    assert pick_audio_track([], "en") is None
