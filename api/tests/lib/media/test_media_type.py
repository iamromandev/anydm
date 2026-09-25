import pytest
from src.lib.media.media_type import media_type

MKV = "matroska,webm"
MP4 = "mov,mp4,m4a,3gp,3g2,mj2"


@pytest.mark.parametrize(
    ("container", "video", "audio", "expected"),
    [
        # The forms #93's matrix put to canPlayType.
        (MP4, "h264", "aac", 'video/mp4; codecs="avc1.640028, mp4a.40.2"'),
        (MP4, "hevc", "aac", 'video/mp4; codecs="hvc1.1.6.L120.90, mp4a.40.2"'),
        (MP4, "av1", "opus", 'video/mp4; codecs="av01.0.08M.08, opus"'),
        (MP4, "vp9", "aac", 'video/mp4; codecs="vp09.00.40.08, mp4a.40.2"'),
        (MP4, "h264", "mp3", 'video/mp4; codecs="avc1.640028, mp3"'),
        (MP4, "h264", "ac3", 'video/mp4; codecs="avc1.640028, ac-3"'),
        (MP4, "h264", "eac3", 'video/mp4; codecs="avc1.640028, ec-3"'),
        (MP4, None, "aac", 'audio/mp4; codecs="mp4a.40.2"'),
        (MP4, "h264", None, 'video/mp4; codecs="avc1.640028"'),
        # Matroska and WebM share ffprobe's name: WebM is the one of only its codecs.
        (MKV, "vp9", "opus", 'video/webm; codecs="vp09.00.40.08, opus"'),
        (MKV, "vp8", "vorbis", 'video/webm; codecs="vp8, vorbis"'),
        (MKV, "av1", "opus", 'video/webm; codecs="av01.0.08M.08, opus"'),
        (MKV, None, "opus", 'audio/webm; codecs="opus"'),
        (MKV, "h264", "aac", 'video/x-matroska; codecs="avc1.640028, mp4a.40.2"'),
        (MKV, "hevc", "ac3", 'video/x-matroska; codecs="hvc1.1.6.L120.90, ac-3"'),
        ("mp3", None, "mp3", "audio/mpeg"),
        ("ogg", None, "opus", 'audio/ogg; codecs="opus"'),
        ("ogg", None, "vorbis", 'audio/ogg; codecs="vorbis"'),
        ("flac", None, "flac", "audio/flac"),
        ("wav", None, "pcm_s16le", "audio/wav"),
    ],
)
def test_media_type(container: str, video: str | None, audio: str | None, expected: str) -> None:
    assert media_type(container, video, audio) == expected


@pytest.mark.parametrize(
    ("container", "video", "audio"),
    [
        # No browser plays these from a file: a session transcodes them.
        ("mpegts", "h264", "aac"),
        ("avi", "mpeg4", "mp3"),
        (MKV, "h264", "dts"),
        (MP4, "mpeg4", "aac"),
        (None, "h264", "aac"),
        (MKV, None, None),
    ],
)
def test_no_media_type_means_a_session(container: str | None, video: str | None, audio: str | None) -> None:
    assert media_type(container, video, audio) is None
