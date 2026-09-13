import pytest
from src.lib.youtube.url import extract_video_id, is_youtube_url

VIDEO_ID = "dQw4w9WgXcQ"


@pytest.mark.parametrize(
    "url",
    [
        f"https://www.youtube.com/watch?v={VIDEO_ID}",
        f"https://youtube.com/watch?v={VIDEO_ID}",
        f"https://m.youtube.com/watch?v={VIDEO_ID}",
        f"https://music.youtube.com/watch?v={VIDEO_ID}",
        f"https://www.youtube.com/watch?v={VIDEO_ID}&t=42s",
        f"https://youtu.be/{VIDEO_ID}",
        f"https://youtu.be/{VIDEO_ID}?t=42",
        f"https://www.youtube.com/embed/{VIDEO_ID}",
        f"https://www.youtube.com/shorts/{VIDEO_ID}",
        f"  https://www.youtube.com/watch?v={VIDEO_ID}  ",
    ],
)
def test_extracts_the_video_id(url: str) -> None:
    assert extract_video_id(url) == VIDEO_ID


@pytest.mark.parametrize(
    "url",
    [
        "",
        "   ",
        "not a url",
        "https://example.com/watch?v=abc",
        "https://www.youtube.com/",
        "https://www.youtube.com/watch",
        "https://www.youtube.com/feed/subscriptions",
        "https://vimeo.com/123456",
        "ftp://youtube.com/watch?v=abc",
    ],
)
def test_returns_none_for_anything_else(url: str) -> None:
    assert extract_video_id(url) is None


def test_is_youtube_url_mirrors_extraction() -> None:
    assert is_youtube_url(f"https://youtu.be/{VIDEO_ID}") is True
    assert is_youtube_url("https://example.com") is False
