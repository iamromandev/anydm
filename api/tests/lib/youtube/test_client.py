from types import SimpleNamespace
from typing import Any

import pytest
from src.core.type import Code
from src.lib.youtube.client import _classify, _to_stream_info


def _raw(**overrides: Any) -> SimpleNamespace:
    base: dict[str, Any] = {
        "itag": 137,
        "mime_type": "video/mp4",
        "resolution": "1080p",
        "_height": 1080,
        "bitrate": 2_500_000,
        "includes_video_track": True,
        "includes_audio_track": False,
        "_filesize": 104_857_600,
    }
    base.update(overrides)
    return SimpleNamespace(**base)


def test_to_stream_info_maps_every_field() -> None:
    info = _to_stream_info(_raw())
    assert info.itag == 137
    assert info.mime_type == "video/mp4"
    assert info.quality == "1080p"
    assert info.height == 1080
    assert info.bitrate == 2_500_000
    assert info.has_video is True
    assert info.has_audio is False
    assert info.content_length == 104_857_600


def test_to_stream_info_handles_audio_only() -> None:
    info = _to_stream_info(
        _raw(
            itag=140,
            mime_type="audio/mp4",
            resolution=None,
            _height=None,
            includes_video_track=False,
            includes_audio_track=True,
        )
    )
    assert info.height is None
    assert info.has_audio is True
    assert info.has_video is False


def test_to_stream_info_falls_back_to_parsing_the_resolution_label() -> None:
    # ``_height`` comes from the stream dict and is absent for some formats;
    # the itag profile's "720p" label is then the only height on offer.
    info = _to_stream_info(_raw(_height=None, resolution="720p"))
    assert info.height == 720


def test_to_stream_info_treats_a_zero_filesize_as_unknown() -> None:
    # pytubefix stores contentLength as 0 when YouTube omits it; the network
    # -fetching ``filesize`` property is deliberately never touched.
    assert _to_stream_info(_raw(_filesize=0)).content_length is None


def test_to_stream_info_survives_missing_attributes() -> None:
    info = _to_stream_info(SimpleNamespace(itag=18))
    assert info.itag == 18
    assert info.mime_type is None
    assert info.height is None
    assert info.content_length is None


@pytest.mark.parametrize(
    ("message", "expected_code"),
    [
        ("Video is private", Code.NOT_FOUND),
        ("This video is unavailable", Code.NOT_FOUND),
        ("Video is age restricted", Code.FORBIDDEN),
        ("members-only content", Code.FORBIDDEN),
        ("something nobody predicted", Code.BAD_GATEWAY),
    ],
)
def test_classify_maps_library_failures(message: str, expected_code: Code) -> None:
    assert _classify(RuntimeError(message)).code == expected_code


def test_only_unknown_failures_are_retryable() -> None:
    assert _classify(RuntimeError("Video is private")).retry_able is False
    assert _classify(RuntimeError("who knows")).retry_able is True
