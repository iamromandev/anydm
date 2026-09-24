import pytest
from src.lib.site.filename import safe_filename


@pytest.mark.parametrize(
    ("title", "suffix", "extension", "expected"),
    [
        ("Never Gonna Give You Up", "1080p", "mp4", "Never_Gonna_Give_You_Up_1080p.mp4"),
        ("Rick / Astley: Live!", "720p", "mp4", "Rick_Astley_Live_720p.mp4"),
        ("  spaced  out  ", "", "mp3", "spaced_out.mp3"),
        ("", "", "mp4", "download.mp4"),
        ("...", "", "mp4", "download.mp4"),
    ],
)
def test_safe_filename(title: str, suffix: str, extension: str, expected: str) -> None:
    assert safe_filename(title, suffix, extension) == expected


def test_safe_filename_keeps_non_ascii_letters() -> None:
    # Python's \w is Unicode-aware, so a title with no ASCII letters keeps its
    # name instead of collapsing to "download".
    assert safe_filename("日本語の動画", "720p", "mp4") == "日本語の動画_720p.mp4"


def test_safe_filename_truncates_very_long_titles() -> None:
    name = safe_filename("x" * 500, "1080p", "mp4")
    assert len(name) <= 200
    assert name.endswith("_1080p.mp4")
