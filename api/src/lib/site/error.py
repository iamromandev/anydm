"""Selection failures, for any site."""

from __future__ import annotations

from src.core.error import Error
from src.core.type import Code, ErrorType


def no_format_for_preset(preset: str) -> Error:
    return Error.create(
        code=Code.UNPROCESSABLE_ENTITY,
        message=f'No stream available for preset "{preset}"',
        error_type=ErrorType.UNPROCESSABLE_ENTITY,
    )
