"""The yt-dlp adapter for any site, driven by #53's recordings.

The fixtures carry no URLs or header values, so the tests add them.
"""

import json
import time
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.lib.site.client import Resolved, YtDlpClient, classify

FIXTURES = Path(__file__).parents[2] / "fixtures" / "ytdlp"
HEADERS = {"User-Agent": "Mozilla/5.0 (test)", "Accept": "*/*"}


def _info(site: str, **overrides: Any) -> dict[str, Any]:
    """An ``extract_info`` result rebuilt from a fixture."""
    fixture = json.loads((FIXTURES / f"{site}.json").read_text())
    info: dict[str, Any] = {
        "extractor_key": fixture["extractor"],
        "id": fixture["id"],
        "title": fixture["title"],
        "uploader": "Someone",
        "duration": fixture["duration"],
        "thumbnail": f"https://img.test/{site}.jpg",
        "webpage_url": fixture["webpage_url"],
        "is_live": False,
        "formats": [
            {**f, "url": f"https://media.test/{site}/{f['format_id']}", "http_headers": dict(HEADERS)}
            for f in fixture["formats"]
        ],
    }
    info.update(overrides)
    return info


def _client(info: dict[str, Any] | None = None, **kwargs: Any) -> YtDlpClient:
    return YtDlpClient(extract=lambda _url: info or _info("vimeo"), **kwargs)


# --- extract -------------------------------------------------------------------


@pytest.mark.asyncio
async def test_extract_maps_the_metadata() -> None:
    info = await _client(_info("vimeo")).extract("http://vimeo.com/75629013")

    assert info.extractor == "Vimeo"
    assert info.id == "75629013"
    assert (info.title, info.uploader) == (_info("vimeo")["title"], "Someone")
    assert info.duration == 187
    assert info.thumbnail == "https://img.test/vimeo.jpg"
    assert info.webpage_url == _info("vimeo")["webpage_url"]
    assert info.is_live is False


@pytest.mark.asyncio
async def test_extract_keeps_every_format_for_selection_to_judge() -> None:
    info = await _client(_info("youtube")).extract("https://youtu.be/dQw4w9WgXcQ")

    ids = {f.id for f in info.formats}
    assert {"137", "140", "233", "sb0"} <= ids
    assert len(info.formats) == len(_info("youtube")["formats"])


@pytest.mark.asyncio
async def test_a_fractional_duration_is_whole_seconds() -> None:
    info = await _client(_info("soundcloud")).extract("http://soundcloud.com/x/y")

    assert info.duration == 143


@pytest.mark.asyncio
async def test_a_single_format_page_is_its_own_format() -> None:
    # Some extractors put the one format on the info dict itself, with no list.
    single = {
        "extractor_key": "Generic",
        "id": "clip",
        "title": "clip",
        "url": "https://media.test/clip.mp4",
        "format_id": "0",
        "protocol": "https",
        "ext": "mp4",
    }
    info = await _client(single).extract("https://example.test/clip")

    assert [f.id for f in info.formats] == ["0"]


@pytest.mark.asyncio
async def test_extract_reports_a_live_stream_as_live() -> None:
    info = await _client(_info("twitch", is_live=True)).extract("https://twitch.tv/x")

    assert info.is_live is True


@pytest.mark.asyncio
async def test_a_playlist_link_is_refused() -> None:
    playlist = {"_type": "playlist", "id": "PL1", "title": "A list", "entries": [_info("youtube")]}

    with pytest.raises(Error) as caught:
        await _client(playlist).extract("https://www.youtube.com/playlist?list=PL1")

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY


# --- resolve -------------------------------------------------------------------


@pytest.mark.asyncio
async def test_resolve_gives_each_part_its_url_and_headers_from_one_extraction() -> None:
    calls: list[str] = []

    def extract(url: str) -> dict[str, Any]:
        calls.append(url)
        return _info("reddit")

    resolved = await YtDlpClient(extract=extract).resolve("https://reddit.test/post", ["dash-VIDEO-1", "dash-AUDIO-1"])

    assert resolved == {
        "dash-VIDEO-1": Resolved("https://media.test/reddit/dash-VIDEO-1", HEADERS),
        "dash-AUDIO-1": Resolved("https://media.test/reddit/dash-AUDIO-1", HEADERS),
    }
    assert calls == ["https://reddit.test/post"]


@pytest.mark.asyncio
async def test_resolve_refuses_a_format_the_site_no_longer_offers() -> None:
    with pytest.raises(Error) as caught:
        await _client(_info("vimeo")).resolve("http://vimeo.com/1", ["http-4320p"])

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY


# --- failures -------------------------------------------------------------------


@pytest.mark.asyncio
async def test_an_extraction_that_hangs_gives_up_and_can_be_retried() -> None:
    def extract(_url: str) -> dict[str, Any]:
        time.sleep(0.3)
        return _info("vimeo")

    with pytest.raises(Error) as caught:
        await YtDlpClient(extract=extract, timeout_s=0.05).extract("http://vimeo.com/1")

    assert (caught.value.code, caught.value.retry_able) == (Code.BAD_GATEWAY, True)


@pytest.mark.asyncio
async def test_a_library_failure_is_classified() -> None:
    def extract(_url: str) -> dict[str, Any]:
        raise RuntimeError("ERROR: Unsupported URL: https://example.test/file.zip")

    with pytest.raises(Error) as caught:
        await YtDlpClient(extract=extract).extract("https://example.test/file.zip")

    assert caught.value.type == ErrorType.UNSUPPORTED_URL


@pytest.mark.parametrize(
    ("message", "code"),
    [
        ("Unsupported URL: https://example.test/file.zip", Code.BAD_REQUEST),
        ("'not a link' is not a valid URL.", Code.BAD_REQUEST),
        ("Private video. Sign in if you've been granted access to this video", Code.NOT_FOUND),
        ("Video unavailable. This video has been removed by the uploader", Code.NOT_FOUND),
        ("Sign in to confirm your age. This video may be inappropriate for some users.", Code.FORBIDDEN),
        ("Join this channel to get access to members-only content like this video", Code.FORBIDDEN),
        ("The uploader has not made this video available in your country", Code.FORBIDDEN),
        # YouTube's bot check clears with time: retry, don't give up.
        ("Sign in to confirm you're not a bot. This helps protect our community.", Code.BAD_GATEWAY),
        ("Unable to download webpage: <urlopen error timed out>", Code.BAD_GATEWAY),
    ],
)
def test_classify(message: str, code: Code) -> None:
    assert classify(RuntimeError(f"ERROR: [site] x: {message}")).code == code


def test_only_the_unknown_is_retryable() -> None:
    assert classify(RuntimeError("Unsupported URL: x")).retry_able is False
    assert classify(RuntimeError("Private video")).retry_able is False
    assert classify(RuntimeError("who knows")).retry_able is True
