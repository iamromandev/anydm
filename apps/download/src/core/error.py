import gc
from functools import reduce
from typing import Annotated, Any

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from loguru import logger
from pydantic import Field

from .base import BaseSchema
from .constants import EXCEPTION_CODE_MAP, EXCEPTION_ERROR_TYPE_MAP
from .formats import utc_iso_timestamp
from .mixins import BaseMixin
from .type import Code, ErrorType, Status


class Violation(BaseSchema):
    field: Annotated[str | None, Field(default=None)] = None
    description: Annotated[str | None, Field(default=None)] = None


class ErrorDetail(BaseSchema):
    subject: Annotated[str | None, Field(default=None)] = None
    description: Annotated[str | None, Field(default=None)] = None
    fields: Annotated[list[Any] | None, Field(default=None)] = None
    violations: Annotated[list[Violation] | None, Field(default=None)] = None


class Error(Exception, BaseMixin):
    def __init__(
        self,
        status: Annotated[Status, Field(...)] = Status.ERROR,
        code: Annotated[Code, Field(...)] = Code.INTERNAL_SERVER_ERROR,
        message: Annotated[str | None, Field(...)] = None,
        type: Annotated[ErrorType | None, Field(...)] = None,
        details: Annotated[list[ErrorDetail] | None, Field(...)] = None,
        retry_able: Annotated[bool, Field(...)] = False,
        timestamp: Annotated[str, Field(...)] = utc_iso_timestamp()
    ) -> None:
        super().__init__()
        self.status = status
        self.code = code
        self.message = message
        self.type = type
        self.details = details
        self.retry_able = retry_able
        self.timestamp = timestamp

    def __str__(self) -> str:
        return (
            f"Error(code={self.code}, message={self.message}, "
            f"type={self.type}, details={self.details}, retry_able={self.retry_able})"
        )

    def to_json(self, exclude_none: bool = True) -> Any:
        json = jsonable_encoder(
            self,
            # exclude={"error": {"code"}},
            exclude_none=exclude_none
        )
        logger.info(f"{self._tag}|to_json: {json}")
        return json

    def to_resp(self) -> JSONResponse:
        return JSONResponse(
            content=self.to_json(),
            status_code=self.code.value,
        )

    @classmethod
    def empty(cls: type["Error"]) -> "Error":
        return cls(
            type=ErrorType.SERVER_ERROR,
            details=[ErrorDetail(
                subject=None,
                description=None,
                fields=None,
                violations=None
            )],
            retry_able=False,
        )

    @classmethod
    def create(
        cls: type["Error"],
        code: Code | None = Code.INTERNAL_SERVER_ERROR,
        message: str | None = None,
        type: ErrorType | None = ErrorType.SERVER_ERROR
    ) -> "Error":
        return cls(
            code=code,
            message=message,
            type=type
        )

    @classmethod
    def bad_request(
        cls: type["Error"],
        message: str | None = None
    ) -> "Error":
        return cls(
            code=Code.BAD_REQUEST,
            message=message,
            type=ErrorType.BAD_REQUEST,
        )

    @classmethod
    def unauthorized(
        cls: type["Error"],
        message: str | None = None
    ) -> "Error":
        return cls(
            code=Code.UNAUTHORIZED,
            message=message,
            type=ErrorType.UNAUTHORIZED,
        )

    @classmethod
    def not_found(
        cls: type["Error"],
        message: str | None = None,
        details: list[str] | None = None,
    ) -> "Error":
        return cls(
            code=Code.NOT_FOUND,
            message=message,
            type=ErrorType.NOT_FOUND,
            details=[
                ErrorDetail(
                    description=d
                )
                for d in details
            ] if details else None
        )

    @classmethod
    def conflict(
        cls: type["Error"],
        message: str | None = None,
        details: list[str] | None = None
    ) -> "Error":
        return cls(
            code=Code.CONFLICT,
            message=message or "Conflict: resource already exists.",
            type=ErrorType.CONFLICT,
            details=[
                ErrorDetail(
                    description=d
                )
                for d in details
            ] if details else None
        )

    @classmethod
    def process_exception(
        cls: type["Error"],
        exception: Exception
    ) -> "Error":
        exception_message: str = str(exception)
        exception_type: type[Exception] = type(exception)

        code: Code = EXCEPTION_CODE_MAP.get(
            exception_type,
            Code.INTERNAL_SERVER_ERROR
        )

        error_type: ErrorType = EXCEPTION_ERROR_TYPE_MAP.get(
            exception_type,
            ErrorType.SERVER_ERROR
        )

        return cls(
            code=code,
            message=exception_message,
            type=error_type,
        )


def config_global_errors(app: FastAPI) -> None:
    @app.exception_handler(Exception)
    async def catch_exception(request: Request, error: Exception) -> JSONResponse:
        error_arg_str = reduce(
            lambda arg_str, arg: arg_str + "\n" + str(arg), error.args, ""
        )
        logger.error(
            f"{type(error).__name__} in {request.url}: {error_arg_str}",
            exc_info=error.__traceback__,
        )
        gc.collect()

        return Error.process_exception(error).to_resp()

    @app.exception_handler(Error)
    async def catch_error(request: Request, error: Error) -> JSONResponse:
        logger.error(f"Global|Error|catch_error(): {str(error)}")
        return error.to_resp()
