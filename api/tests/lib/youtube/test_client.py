"""The yt-dlp adapter, driven by what yt-dlp really reported for a YouTube video.

The fixture is the #53 recording (``tests/fixtures/ytdlp/youtube.json``). URLs
are added here, because the fixture deliberately carries none.
"""

import json
import time
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.core.type import Code
from src.data.type import Preset
from src.lib.youtube.client import YtDlpClient, _classify, _to_stream_info
from src.lib.youtube.format import select_plan

FIXTURE = json.loads(
    (Path(__file__).parents[2] / "fixtures" / "ytdlp" / "youtube.json").read_text()
)
FORMATS: list[dict[str, Any]] = FIXTURE["formats"]


def _format(format_id: str) -> dict[str, Any]:
    return next(f for f in FORMATS if f["format_id"] == format_id)


def _streams() -> list[Any]:
    return [s for s in (_to_stream_info(f) for f in FORMATS) if s is not None]


def _info(**overrides: Any) -> dict[str, Any]:
    """An ``extract_info`` result: the fixture's formats, with URLs, and metadata."""
    info: dict[str, Any] = {
        "id": "dQw4w9WgXcQ",
        "title": FIXTURE["title"],
        "uploader": "Rick Astley",
        "channel_id": "UCuAXFkgsw1L7xaCfnd5JJOw",
        "description": "The official video",
        "duration": FIXTURE["duration"],
        "view_count": 1_700_000_000,
        "upload_date": "20091025",
        "is_live": False,
        "thumbnail": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
        "formats": [{**f, "url": f"https://media.test/{f['format_id']}"} for f in FORMATS],
    }
    info.update(overrides)
    return info


def _client(extract: Any = None, **kwargs: Any) -> YtDlpClient:
    return YtDlpClient(extract=extract or (lambda _url: _info()), **kwargs)


# --- formats -----------------------------------------------------------------


def test_only_plain_https_formats_become_streams() -> None:
    # HLS formats carry numeric ids too (233, 602, ...), and storyboards are
    # images: neither is something the engine can fetch as a file.
    itags = {s.itag for s in _streams()}

    assert 137 in itags and 140 in itags and 18 in itags
    assert not itags & {233, 234, 602, 269, 270, 625}
    assert all(f["protocol"] == "https" for f in FORMATS if f["format_id"] in {str(i) for i in itags})


def test_a_video_only_format_maps_every_field() -> None:
    stream = _to_stream_info(_format("137"))

    assert stream is not None
    assert (stream.itag, stream.mime_type, stream.quality, stream.height) == (137, "video/mp4", "1080p", 1080)
    assert (stream.has_video, stream.has_audio) == (True, False)
    assert stream.bitrate == 3_038_377
    assert stream.content_length == 80_911_999


def test_an_m4a_audio_format_is_audio_mp4() -> None:
    stream = _to_stream_info(_format("140"))

    assert stream is not None
    assert (stream.mime_type, stream.height, stream.quality) == ("audio/mp4", None, None)
    assert (stream.has_video, stream.has_audio) == (False, True)
    assert stream.bitrate == 129_502


def test_a_webm_audio_format_is_audio_webm() -> None:
    stream = _to_stream_info(_format("251"))

    assert stream is not None and stream.mime_type == "audio/webm"


def test_the_combined_format_has_both_tracks() -> None:
    stream = _to_stream_info(_format("18"))

    assert stream is not None
    assert (stream.has_video, stream.has_audio, stream.height) == (True, True, 360)


def test_a_missing_size_is_unknown_rather_than_zero() -> None:
    stream = _to_stream_info({**_format("137"), "filesize": None})

    assert stream is not None and stream.content_length is None


def test_youtube_presets_pick_h264_video_and_the_m4a_track() -> None:
    # The video itags are the ones pytubefix led to. The audio is not: pytubefix
    # reported YouTube's peak bitrate, where Opus 251 edges out AAC 140, and
    # yt-dlp reports the average, where 140 edges out 251. Both are ~129 kbps,
    # and AAC is the better track to mux into MP4 anyway.
    streams = _streams()

    assert (select_plan(streams, Preset.P1080).video_itag, select_plan(streams, Preset.P1080).audio_itag) == (137, 140)
    assert (select_plan(streams, Preset.P720).video_itag, select_plan(streams, Preset.P720).audio_itag) == (136, 140)
    assert select_plan(streams, Preset.MP3).audio_itag == 140
    assert select_plan(streams, Preset.P1080).expected_bytes == 80_911_999 + 3_449_447


# --- the client --------------------------------------------------------------


@pytest.mark.asyncio
async def test_fetch_info_maps_the_metadata() -> None:
    info = await _client().fetch_info("dQw4w9WgXcQ")

    assert info.video_id == "dQw4w9WgXcQ"
    assert info.title == FIXTURE["title"]
    assert (info.author, info.channel_id) == ("Rick Astley", "UCuAXFkgsw1L7xaCfnd5JJOw")
    assert (info.length_seconds, info.view_count) == (213, 1_700_000_000)
    assert info.upload_date == "2009-10-25"
    assert info.is_live is False
    assert [t.url for t in info.thumbnails] == ["https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"]
    assert len(info.streams) == len(_streams())


@pytest.mark.asyncio
async def test_fetch_info_asks_for_the_watch_page() -> None:
    asked: list[str] = []

    def extract(url: str) -> dict[str, Any]:
        asked.append(url)
        return _info()

    await _client(extract).fetch_info("dQw4w9WgXcQ")

    assert asked == ["https://www.youtube.com/watch?v=dQw4w9WgXcQ"]


@pytest.mark.asyncio
async def test_stream_url_is_the_https_url_of_that_itag() -> None:
    assert await _client().stream_url("dQw4w9WgXcQ", 137) == "https://media.test/137"


@pytest.mark.asyncio
async def test_stream_url_refuses_an_itag_that_is_only_offered_as_hls() -> None:
    with pytest.raises(Error) as caught:
        await _client().stream_url("dQw4w9WgXcQ", 270)

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY


@pytest.mark.asyncio
async def test_a_library_failure_is_classified() -> None:
    def extract(_url: str) -> dict[str, Any]:
        raise RuntimeError("ERROR: [youtube] dQw4w9WgXcQ: Private video. Sign in if you've been granted access")

    with pytest.raises(Error) as caught:
        await _client(extract).fetch_info("dQw4w9WgXcQ")

    assert caught.value.code == Code.NOT_FOUND


@pytest.mark.asyncio
async def test_an_extraction_that_hangs_gives_up_and_can_be_retried() -> None:
    def extract(_url: str) -> dict[str, Any]:
        time.sleep(0.3)
        return _info()

    with pytest.raises(Error) as caught:
        await _client(extract, timeout_s=0.05).fetch_info("dQw4w9WgXcQ")

    assert caught.value.code == Code.BAD_GATEWAY
    assert caught.value.retry_able is True


# --- failures ----------------------------------------------------------------


@pytest.mark.parametrize(
    ("message", "expected_code"),
    [
        ("Private video. Sign in if you've been granted access to this video", Code.NOT_FOUND),
        ("Video unavailable. This video has been removed by the uploader", Code.NOT_FOUND),
        ("Sign in to confirm your age. This video may be inappropriate for some users.", Code.FORBIDDEN),
        ("Join this channel to get access to members-only content like this video", Code.FORBIDDEN),
        ("The uploader has not made this video available in your country", Code.FORBIDDEN),
        # YouTube's bot check passes with time and a different IP: retry, don't give up.
        ("Sign in to confirm you're not a bot. This helps protect our community.", Code.BAD_GATEWAY),
        ("Unable to download API page: <urlopen error timed out>", Code.BAD_GATEWAY),
    ],
)
def test_classify_maps_yt_dlp_failures(message: str, expected_code: Code) -> None:
    assert _classify(RuntimeError(f"ERROR: [youtube] dQw4w9WgXcQ: {message}")).code == expected_code


def test_only_unknown_failures_are_retryable() -> None:
    assert _classify(RuntimeError("Private video")).retry_able is False
    assert _classify(RuntimeError("who knows")).retry_able is True
