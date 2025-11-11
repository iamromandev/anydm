import gc
import traceback
from typing import Annotated, Any

from fastapi import FastAPI, Request, status
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from loguru import logger
from pydantic import Field

from .base import BaseSchema
from .constant import EXCEPTION_CODE_MAP, EXCEPTION_ERROR_TYPE_MAP
from .format import utc_iso_timestamp
from .mixin import BaseMixin
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
        status: Status = Status.ERROR,
        code: Code = Code.INTERNAL_SERVER_ERROR,
        message: str | None = None,
        type: ErrorType | None = None,
        details: list[ErrorDetail] | None = None,
        retry_able: bool = False,
        timestamp: str | None = None,
    ) -> None:
        super().__init__()
        self.status = status
        self.code = code
        self.message = message
        self.type = type
        self.details = details
        self.retry_able = retry_able
        self.timestamp = timestamp or utc_iso_timestamp()

    def __str__(self) -> str:
        return (
            f"Error(code={self.code}, message={self.message}, "
            f"type={self.type}, details={self.details}, retry_able={self.retry_able})"
        )

    def to_dict(self, exclude_none: bool = True) -> dict[str, Any]:
        data = {
            "status": self.status,
            "code": self.code,
            "message": self.message,
            "type": self.type,
            "details": self.details,
            "retry_able": self.retry_able,
            "timestamp": self.timestamp,
        }
        if exclude_none:
            data = {k: v for k, v in data.items() if v is not None}
        return data

    def to_json(self, exclude_none: bool = True) -> dict[str, Any]:
        data = self.to_dict(exclude_none=exclude_none)
        json = jsonable_encoder(data)
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

    @classmethod
    def process_validation_error(
        cls: type["Error"],
        exc: RequestValidationError
    ) -> "Error":
        messages = [
            f"{'.'.join(str(loc) for loc in err['loc'])}: {err['msg']}"
            for err in exc.errors()
        ]
        exception_message = "; ".join(messages) if messages else "Validation error"

        return cls(
            code=Code.UNPROCESSABLE_ENTITY,  # 422
            message=exception_message,
            type=ErrorType.VALIDATION_ERROR,
        )


def init_global_errors(app: FastAPI) -> None:
    # Keep your existing FastAPI exception handlers for direct Exceptions, ValidationError, and Error instances
    @app.exception_handler(Exception)
    async def catch_exception(request: Request, error: Exception) -> JSONResponse:
        tb_str = "".join(traceback.format_exception(type(error), error, error.__traceback__))
        logger.error(f"FastAPIHandlerError at {request.url}:\n{tb_str}")
        gc.collect()
        return Error.process_exception(error).to_resp()

    # Validation error handler
    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        logger.warning(f"Validation error at {request.url}: {exc.errors()}")
        return Error.process_validation_error(exc).to_resp()

    # Direct Error instances
    @app.exception_handler(Error)
    async def catch_custom_error(request: Request, error: Error) -> JSONResponse:
        logger.error(f"CustomError at {request.url}: {str(error)}")
        return error.to_resp()


def error_api_responses() -> dict[int, dict[str, Any]]:
    return {
        # status.HTTP_200_OK: {
        #     "description": "Successful response",
        #     "model": Success[model_success],
        # },
        status.HTTP_400_BAD_REQUEST: {
            "description": "Bad Request",
            "model": Error,
        },
        status.HTTP_401_UNAUTHORIZED: {
            "description": "Unauthorized",
            "model": Error,
        },
        status.HTTP_403_FORBIDDEN: {
            "description": "Forbidden",
            "model": Error,
        },
        status.HTTP_404_NOT_FOUND: {
            "description": "Resource Not Found",
            "model": Error,
        },
        status.HTTP_422_UNPROCESSABLE_ENTITY: {
            "description": "Validation Error",
            "model": Error,
        },
        status.HTTP_500_INTERNAL_SERVER_ERROR: {
            "description": "Internal Server Error",
            "model": Error,
        },
    }
