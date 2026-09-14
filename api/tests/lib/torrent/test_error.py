from src.core.type import Code, ErrorType
from src.lib.torrent import error as torrent_error


def test_invalid_source_is_a_client_error() -> None:
    err = torrent_error.invalid_source("Empty torrent input")
    assert err.code == Code.BAD_REQUEST
    assert err.retry_able is False


def test_metadata_timeout_is_retry_able() -> None:
    err = torrent_error.metadata_timeout(30)
    assert err.code == Code.REQUEST_TIMEOUT
    assert err.type == ErrorType.TIMEOUT
    assert err.retry_able is True
    assert "30s" in (err.message or "")


def test_engine_unavailable_is_retry_able() -> None:
    err = torrent_error.engine_unavailable("connection refused")
    assert err.code == Code.SERVICE_UNAVAILABLE
    assert err.retry_able is True


def test_engine_rejected_is_permanent() -> None:
    err = torrent_error.engine_rejected("bad magnet")
    assert err.code == Code.BAD_GATEWAY
    assert err.retry_able is False


def test_torrent_not_found_names_the_hash() -> None:
    err = torrent_error.torrent_not_found("abc123")
    assert err.code == Code.NOT_FOUND
    assert "abc123" in (err.message or "")
