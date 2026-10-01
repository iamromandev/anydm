import uuid

from src.core.error import Error
from src.core.type import Code, ErrorType


def source_not_found(id: uuid.UUID) -> Error:
    return Error.create(
        code=Code.NOT_FOUND,
        message=f"There is no source with id {id}",
        error_type=ErrorType.SOURCE_NOT_FOUND,
    )


def not_deletable(name: str) -> Error:
    return Error.create(
        code=Code.UNPROCESSABLE_ENTITY,
        message=f"{name} is built in: switch it off instead of deleting it",
        error_type=ErrorType.SOURCE_NOT_DELETABLE,
    )


def no_default(name: str) -> Error:
    return Error.create(
        code=Code.UNPROCESSABLE_ENTITY,
        message=f"{name} has no built-in address to go back to",
        error_type=ErrorType.SOURCE_NO_DEFAULT,
    )


def invalid_value(message: str) -> Error:
    return Error.create(
        code=Code.UNPROCESSABLE_ENTITY,
        message=message,
        error_type=ErrorType.UNPROCESSABLE_ENTITY,
    )


def invalid_address(message: str) -> Error:
    return Error.create(
        code=Code.UNPROCESSABLE_ENTITY,
        message=message,
        error_type=ErrorType.UNPROCESSABLE_ENTITY,
    )
