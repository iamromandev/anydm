from src.core.error import Error
from src.core.type import Code, ErrorType


def test_not_found_carries_code_and_type() -> None:
    err = Error.not_found(message="Task not found")
    assert err.code == Code.NOT_FOUND
    assert err.message == "Task not found"
    assert err.retry_able is False


def test_retry_able_is_settable() -> None:
    err = Error.create(
        code=Code.BAD_GATEWAY,
        message="upstream broke",
        error_type=ErrorType.EXTERNAL_API_ERROR,
        retry_able=True,
    )
    assert err.retry_able is True
    assert err.type == ErrorType.EXTERNAL_API_ERROR


def test_create_defaults_to_permanent() -> None:
    assert Error.create(code=Code.NOT_FOUND).retry_able is False


def test_to_resp_uses_the_code_as_status() -> None:
    assert Error.not_found().to_resp().status_code == 404


def test_service_unavailable_is_retry_able() -> None:
    err = Error.service_unavailable(message="Torrent service is unreachable")
    assert err.code == Code.SERVICE_UNAVAILABLE
    assert err.type == ErrorType.SERVICE_UNAVAILABLE
    assert err.message == "Torrent service is unreachable"
    assert err.retry_able is True
    assert err.to_resp().status_code == 503
