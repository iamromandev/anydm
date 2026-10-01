"""What a source's failure reads as: the same words in a search's error line and in a Test."""

import httpx

from src.lib.sources.source import SourceError


def failure_message(error: Exception, timeout_s: float) -> str | None:
    """The words for a failure a source can have, or ``None`` for anything else (which is a bug, and propagates)."""
    if isinstance(error, httpx.TimeoutException):
        return f"timed out after {timeout_s:g} s"
    if isinstance(error, httpx.HTTPError):
        return "couldn't reach it"
    if isinstance(error, SourceError):
        return error.message
    return None
