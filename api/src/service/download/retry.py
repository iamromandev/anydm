"""The retry policy. Pure.

``Error.retry_able`` is the whole decision — it is set where the failure is
raised, by the code that knows whether trying again could possibly help.
"""

from __future__ import annotations

from dataclasses import dataclass

from src.core.error import Error

#: Seconds to wait before attempt N+1. The last entry repeats for any attempt
#: beyond the schedule.
_SCHEDULE = (5, 30, 120)


def backoff_seconds(attempt: int) -> int:
    index = min(max(attempt, 1), len(_SCHEDULE)) - 1
    return _SCHEDULE[index]


@dataclass(frozen=True, slots=True)
class RetryDecision:
    retry: bool
    delay_seconds: int = 0


def decide(error: Error, attempts: int, max_attempts: int) -> RetryDecision:
    """``attempts`` counts tries including this one, so the Nth failure is final."""
    if not error.retry_able or attempts >= max_attempts:
        return RetryDecision(retry=False)
    return RetryDecision(retry=True, delay_seconds=backoff_seconds(attempts))
