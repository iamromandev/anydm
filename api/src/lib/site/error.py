"""Site failures as this project's ``Error``.

Every one carries ``retry_able``, the only thing the worker consults when
deciding between a retry and a dead task.
"""

from __future__ import annotations

from src.core.error import Error
from src.core.type import Code, ErrorType


def no_format_for_preset(preset: str) -> Error:
    return Error.create(
        code=Code.UNPROCESSABLE_ENTITY,
        message=f'No stream available for preset "{preset}"',
        error_type=ErrorType.UNPROCESSABLE_ENTITY,
    )


def unsupported_url(reason: str) -> Error:
    return Error.create(
        code=Code.BAD_REQUEST,
        message=f"No site supports this link: {reason}",
        error_type=ErrorType.UNSUPPORTED_URL,
    )


def playlist_not_supported() -> Error:
    return Error.create(
        code=Code.UNPROCESSABLE_ENTITY,
        message="Playlists and channels are not supported yet; link a single video",
        error_type=ErrorType.UNSUPPORTED_OPERATION,
    )


def live_not_supported() -> Error:
    return Error.create(
        code=Code.UNPROCESSABLE_ENTITY,
        message="Live streams cannot be downloaded or played yet",
        error_type=ErrorType.UNSUPPORTED_OPERATION,
    )


def stream_not_playable() -> Error:
    """Every format is a DASH, f4m or ISM manifest, which ffmpeg does not play by URL."""
    return Error.create(
        code=Code.UNPROCESSABLE_ENTITY,
        message="The player can't read this site's streams yet; it can still be downloaded",
        error_type=ErrorType.UNSUPPORTED_OPERATION,
    )


def media_unavailable(reason: str) -> Error:
    return Error.create(
        code=Code.NOT_FOUND,
        message=f"Media unavailable: {reason}",
        error_type=ErrorType.DOES_NOT_EXIST,
    )


def media_forbidden(reason: str) -> Error:
    return Error.create(
        code=Code.FORBIDDEN,
        message=f"Media not accessible: {reason}",
        error_type=ErrorType.FORBIDDEN,
    )


def extraction_failed(reason: str) -> Error:
    """Retryable: this is what a site-side change or a bot check looks like from here."""
    return Error.create(
        code=Code.BAD_GATEWAY,
        message=f"Extraction failed: {reason}",
        error_type=ErrorType.EXTERNAL_API_ERROR,
        retry_able=True,
    )


def transfer_failed(reason: str) -> Error:
    """Retryable: a fragment still failing after yt-dlp's own retries, most often an expired URL."""
    return Error.create(
        code=Code.BAD_GATEWAY,
        message=f"Download failed: {reason}",
        error_type=ErrorType.EXTERNAL_API_ERROR,
        retry_able=True,
    )
