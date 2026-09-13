import pytest
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.service.download.retry import backoff_seconds, decide


def _retryable() -> Error:
    return Error.create(code=Code.BAD_GATEWAY, error_type=ErrorType.EXTERNAL_API_ERROR, retry_able=True)


def _permanent() -> Error:
    return Error.create(code=Code.NOT_FOUND, error_type=ErrorType.DOES_NOT_EXIST, retry_able=False)


@pytest.mark.parametrize(("attempt", "seconds"), [(1, 5), (2, 30), (3, 120), (4, 120), (99, 120)])
def test_backoff_schedule(attempt: int, seconds: int) -> None:
    assert backoff_seconds(attempt) == seconds


def test_a_permanent_error_never_retries() -> None:
    assert decide(_permanent(), attempts=1, max_attempts=3).retry is False


def test_a_retryable_error_retries_while_attempts_remain() -> None:
    decision = decide(_retryable(), attempts=1, max_attempts=3)
    assert decision.retry is True
    assert decision.delay_seconds == 5


def test_the_second_retry_waits_longer() -> None:
    assert decide(_retryable(), attempts=2, max_attempts=3).delay_seconds == 30


def test_the_last_attempt_does_not_retry() -> None:
    # attempts counts tries, so with max_attempts=3 the third failure is final.
    assert decide(_retryable(), attempts=3, max_attempts=3).retry is False


def test_max_attempts_of_one_never_retries() -> None:
    assert decide(_retryable(), attempts=1, max_attempts=1).retry is False
