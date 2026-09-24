"""YouTube failures as this project's ``Error``.

Every one carries ``retry_able``, which is the only thing the worker consults
when deciding between a retry and a dead task — see
``service/download/download_worker.py``.
"""

from __future__ import annotations

from src.core.error import Error
from src.core.type import Code, ErrorType

# Shared with every site's selection; re-exported so YouTube callers keep one import.
from src.lib.site.error import no_format_for_preset as no_format_for_preset


def not_a_youtube_url() -> Error:
    return Error.create(
        code=Code.BAD_REQUEST,
        message="Not a YouTube URL",
        error_type=ErrorType.UNSUPPORTED_OPERATION,
    )


def video_unavailable(reason: str) -> Error:
    return Error.create(
        code=Code.NOT_FOUND,
        message=f"Video unavailable: {reason}",
        error_type=ErrorType.DOES_NOT_EXIST,
    )


def video_forbidden(reason: str) -> Error:
    return Error.create(
        code=Code.FORBIDDEN,
        message=f"Video not accessible: {reason}",
        error_type=ErrorType.FORBIDDEN,
    )


def extraction_failed(reason: str) -> Error:
    """Retryable: this is what a YouTube-side change looks like from here."""
    return Error.create(
        code=Code.BAD_GATEWAY,
        message=f"YouTube extraction failed: {reason}",
        error_type=ErrorType.EXTERNAL_API_ERROR,
        retry_able=True,
    )
