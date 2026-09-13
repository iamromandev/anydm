from src.core.type import Code, ErrorType
from src.lib.youtube import error as yt_error


def test_not_a_youtube_url_is_a_permanent_400() -> None:
    err = yt_error.not_a_youtube_url()
    assert err.code == Code.BAD_REQUEST
    assert err.type == ErrorType.UNSUPPORTED_OPERATION
    assert err.retry_able is False


def test_video_unavailable_is_a_permanent_404() -> None:
    err = yt_error.video_unavailable("private video")
    assert err.code == Code.NOT_FOUND
    assert err.type == ErrorType.DOES_NOT_EXIST
    assert err.retry_able is False
    assert err.message is not None
    assert "private video" in err.message


def test_video_forbidden_is_a_permanent_403() -> None:
    err = yt_error.video_forbidden("age restricted")
    assert err.code == Code.FORBIDDEN
    assert err.retry_able is False


def test_no_format_for_preset_is_a_permanent_422() -> None:
    err = yt_error.no_format_for_preset("2160")
    assert err.code == Code.UNPROCESSABLE_ENTITY
    assert err.retry_able is False
    assert err.message is not None
    assert "2160" in err.message


def test_extraction_failed_is_a_retryable_502() -> None:
    err = yt_error.extraction_failed("player script changed")
    assert err.code == Code.BAD_GATEWAY
    assert err.type == ErrorType.EXTERNAL_API_ERROR
    assert err.retry_able is True
