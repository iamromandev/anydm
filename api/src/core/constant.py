from __future__ import annotations

from tortoise.exceptions import DoesNotExist, IntegrityError

from src.core.type import Code, ErrorType

EXCEPTION_CODE_MAP: dict[type[Exception], Code] = {
    ValueError: Code.BAD_REQUEST,
    TypeError: Code.BAD_REQUEST,
    KeyError: Code.NOT_FOUND,
    PermissionError: Code.FORBIDDEN,
    FileNotFoundError: Code.NOT_FOUND,
    TimeoutError: Code.REQUEST_TIMEOUT,
    DoesNotExist: Code.NOT_FOUND,
    IntegrityError: Code.CONFLICT,
}

EXCEPTION_ERROR_TYPE_MAP: dict[type[Exception], ErrorType] = {
    ValueError: ErrorType.VALUE_ERROR,
    TypeError: ErrorType.TYPE_ERROR,
    KeyError: ErrorType.DOES_NOT_EXIST,
    PermissionError: ErrorType.FORBIDDEN,
    FileNotFoundError: ErrorType.FILE_NOT_FOUND,
    TimeoutError: ErrorType.TIMEOUT,
    DoesNotExist: ErrorType.DOES_NOT_EXIST,
    IntegrityError: ErrorType.UNIQUE_CONSTRAINT_VIOLATION,
}
