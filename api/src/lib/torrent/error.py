"""Torrent failures as this project's ``Error``.

Mirrors ``src/lib/site/error.py``: every failure that crosses out of the
torrent library is one of these, so no caller has to know what an rqbit error
body looks like. ``retry_able`` carries the transient-or-not decision.
"""

from __future__ import annotations

from src.core.error import Error
from src.core.type import Code, ErrorType


def invalid_source(reason: str) -> Error:
    return Error.create(
        code=Code.BAD_REQUEST,
        message=f"Invalid torrent: {reason}",
        error_type=ErrorType.BAD_REQUEST,
    )


def metadata_timeout(seconds: int) -> Error:
    return Error.create(
        code=Code.REQUEST_TIMEOUT,
        message=f"No peer supplied torrent metadata within {seconds}s",
        error_type=ErrorType.TIMEOUT,
        retry_able=True,
    )


def engine_unavailable(reason: str) -> Error:
    """The torrent service is not answering at all."""
    return Error.service_unavailable(message=f"Torrent service unavailable: {reason}")


def engine_rejected(reason: str) -> Error:
    """The torrent service answered, with a refusal."""
    return Error.create(
        code=Code.BAD_GATEWAY,
        message=f"Torrent service rejected the request: {reason}",
        error_type=ErrorType.BAD_GATEWAY,
    )


def torrent_not_found(info_hash: str) -> Error:
    return Error.not_found(message=f"Torrent {info_hash} is not in the session")
