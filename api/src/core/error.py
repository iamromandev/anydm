from __future__ import annotations

import gc
import traceback
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from loguru import logger
from pydantic import Field
from starlette import status as http_status

from src.core.base import BaseSchema
from src.core.constant import EXCEPTION_CODE_MAP, EXCEPTION_ERROR_TYPE_MAP
from src.core.format import utc_iso_timestamp
from src.core.mixin import BaseMixin
from src.core.redact import redact
from src.core.type import Code, ErrorType, Status

_SAFE_EXCEPTION_TYPES: tuple[type[Exception], ...] = (ValueError,)

_GENERIC_MESSAGES: dict[Code, str] = {
    Code.BAD_REQUEST: "Invalid request",
    Code.UNAUTHORIZED: "Unauthorized",
    Code.FORBIDDEN: "Forbidden",
    Code.NOT_FOUND: "Resource not found",
    Code.METHOD_NOT_ALLOWED: "Method not allowed",
    Code.REQUEST_TIMEOUT: "Request timed out",
    Code.CONFLICT: "Conflict",
    Code.UNPROCESSABLE_ENTITY: "Validation error",
    Code.TOO_MANY_REQUESTS: "Too many requests",
}


class Violation(BaseSchema):
    field: str | None = Field(default=None)
    description: str | None = Field(default=None)


class ErrorDetail(BaseSchema):
    subject: str | None = Field(default=None)
    description: str | None = Field(default=None)
    fields: list[Any] | None = Field(default=None)
    violations: list[Violation] | None = Field(default=None)


class ErrorResponse(BaseSchema):
    """OpenAPI / documentation shape for error JSON (``type`` is the API field name)."""

    status: Status = Status.ERROR
    code: Code
    message: str | None = None
    error_type: ErrorType | None = Field(default=None, serialization_alias="type")
    details: list[ErrorDetail] | None = None
    retry_able: bool = False
    timestamp: str | None = None


class Error(Exception, BaseMixin):
    def __init__(
        self,
        status: Status = Status.ERROR,
        code: Code = Code.INTERNAL_SERVER_ERROR,
        message: str | None = None,
        error_type: ErrorType | None = None,
        details: list[ErrorDetail] | None = None,
        retry_able: bool = False,
        timestamp: str | None = None,
    ) -> None:
        super().__init__()
        self.status = status
        self.code = code
        self.message = message
        self.type = error_type
        self.details = details
        self.retry_able = retry_able
        self.timestamp = timestamp or utc_iso_timestamp()

    def __str__(self) -> str:
        return (
            f"Error(code={self.code}, message={self.message}, "
            f"type={self.type}, details={self.details}, retry_able={self.retry_able})"
        )

    def to_dict(self, exclude_none: bool = True) -> dict[str, Any]:
        data: dict[str, Any] = {
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
        out = jsonable_encoder(data)
        logger.info(f"{self._tag}|to_json: {out}")
        return out

    def to_resp(self) -> JSONResponse:
        return JSONResponse(
            content=self.to_json(),
            status_code=int(self.code),
        )

    @classmethod
    def empty(cls) -> Error:
        return cls(
            error_type=ErrorType.SERVER_ERROR,
            details=[
                ErrorDetail(
                    subject=None,
                    description=None,
                    fields=None,
                    violations=None,
                )
            ],
            retry_able=False,
        )

    @classmethod
    def create(
        cls,
        code: Code | None = Code.INTERNAL_SERVER_ERROR,
        message: str | None = None,
        error_type: ErrorType | None = ErrorType.SERVER_ERROR,
        details: list[ErrorDetail] | None = None,
        retry_able: bool = False,
    ) -> Error:
        """Build an error, including whether trying again could help.

        ``retry_able`` is the whole of the download worker's retry decision, so
        it is set here — where the failure is raised, by the code that knows
        whether the cause is transient. It defaults to ``False``: a failure is
        permanent unless something says otherwise.
        """
        return cls(
            code=code or Code.INTERNAL_SERVER_ERROR,
            message=message,
            error_type=error_type,
            details=details,
            retry_able=retry_able,
        )

    @classmethod
    def bad_request(cls, message: str | None = None) -> Error:
        return cls(
            code=Code.BAD_REQUEST,
            message=message,
            error_type=ErrorType.BAD_REQUEST,
        )

    @classmethod
    def internal(cls, message: str | None = None) -> Error:
        return cls(
            code=Code.INTERNAL_SERVER_ERROR,
            message=message,
            error_type=ErrorType.SERVER_ERROR,
        )

    @classmethod
    def request_timeout(cls, message: str | None = None) -> Error:
        return cls(
            code=Code.REQUEST_TIMEOUT,
            message=message,
            error_type=ErrorType.TIMEOUT,
        )

    @classmethod
    def unauthorized(cls, message: str | None = None) -> Error:
        return cls(
            code=Code.UNAUTHORIZED,
            message=message,
            error_type=ErrorType.UNAUTHORIZED,
        )

    @classmethod
    def service_unavailable(cls, message: str | None = None) -> Error:
        """A dependency this service does not own is not answering.

        ``retry_able`` is True because that is what it means: the caller, or a
        worker, should try again rather than treat the request as wrong.
        """
        return cls(
            code=Code.SERVICE_UNAVAILABLE,
            message=message,
            error_type=ErrorType.SERVICE_UNAVAILABLE,
            retry_able=True,
        )

    @classmethod
    def not_found(
        cls,
        message: str | None = None,
        details: list[str] | None = None,
    ) -> Error:
        return cls(
            code=Code.NOT_FOUND,
            message=message,
            error_type=ErrorType.NOT_FOUND,
            details=[ErrorDetail(description=d) for d in details] if details else None,
        )

    @classmethod
    def forbidden(
        cls,
        message: str | None = None,
        details: list[str] | None = None,
    ) -> Error:
        return cls(
            code=Code.FORBIDDEN,
            message=message,
            error_type=ErrorType.FORBIDDEN,
            details=[ErrorDetail(description=d) for d in details] if details else None,
        )

    @classmethod
    def conflict(
        cls,
        message: str | None = None,
        details: list[ErrorDetail | str] | None = None,
    ) -> Error:
        normalized: list[ErrorDetail] | None = None
        if details is not None:
            normalized = [
                d if isinstance(d, ErrorDetail) else ErrorDetail(description=d)
                for d in details
            ]
        return cls(
            code=Code.CONFLICT,
            message=message or "Conflict: resource already exists.",
            error_type=ErrorType.CONFLICT,
            details=normalized,
        )

    @classmethod
    def process_exception(cls, exception: Exception) -> Error:
        exception_message = str(exception)
        exception_cls = type(exception)

        code = EXCEPTION_CODE_MAP.get(exception_cls, Code.INTERNAL_SERVER_ERROR)
        mapped_type = EXCEPTION_ERROR_TYPE_MAP.get(exception_cls, ErrorType.SERVER_ERROR)

        message = exception_message if exception_cls in _SAFE_EXCEPTION_TYPES else None
        if message is None:
            message = _GENERIC_MESSAGES.get(code, "Internal server error")

        return cls(
            code=code,
            message=message,
            error_type=mapped_type,
        )

    @classmethod
    def process_validation_error(cls, exc: RequestValidationError) -> Error:
        messages: list[str] = []

        for err in exc.errors():
            loc = err.get("loc", ())
            if loc and loc[0] == "body":
                loc = loc[1:]
            field = ".".join(str(p) for p in loc)
            messages.append(f"{field}: {err['msg']}")

        return cls(
            code=Code.UNPROCESSABLE_ENTITY,
            message="; ".join(messages) if messages else "Validation error",
            error_type=ErrorType.VALIDATION_ERROR,
        )


_HTTP_ERROR_TYPES: dict[int, ErrorType] = {
    http_status.HTTP_400_BAD_REQUEST: ErrorType.BAD_REQUEST,
    http_status.HTTP_401_UNAUTHORIZED: ErrorType.UNAUTHORIZED,
    http_status.HTTP_403_FORBIDDEN: ErrorType.FORBIDDEN,
    http_status.HTTP_404_NOT_FOUND: ErrorType.NOT_FOUND,
    http_status.HTTP_405_METHOD_NOT_ALLOWED: ErrorType.METHOD_NOT_ALLOWED,
    http_status.HTTP_409_CONFLICT: ErrorType.CONFLICT,
    http_status.HTTP_422_UNPROCESSABLE_CONTENT: ErrorType.UNPROCESSABLE_ENTITY,
    http_status.HTTP_429_TOO_MANY_REQUESTS: ErrorType.TOO_MANY_REQUESTS,
}


def init_global_errors(app: FastAPI) -> None:
    @app.exception_handler(HTTPException)
    async def http_exception_handler(_request: Request, exc: HTTPException) -> JSONResponse:
        try:
            code = Code(exc.status_code)
        except ValueError:
            code = Code.INTERNAL_SERVER_ERROR
        if isinstance(exc.detail, dict):
            error = Error(
                code=code,
                message=exc.detail.get("message"),
                error_type=_HTTP_ERROR_TYPES.get(exc.status_code),
                details=[
                    ErrorDetail(**d) for d in exc.detail.get("details", [])
                ] if exc.detail.get("details") else None,
                retry_able=False,
            )
        else:
            error = Error(
                code=code,
                message=str(exc.detail) if isinstance(exc.detail, str) else None,
                error_type=_HTTP_ERROR_TYPES.get(exc.status_code),
                retry_able=False,
            )
        resp = error.to_resp()
        if exc.headers and "WWW-Authenticate" in exc.headers:
            resp.headers["WWW-Authenticate"] = exc.headers["WWW-Authenticate"]
        return resp

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        logger.warning(f"Validation error at {redact(str(request.url))}: {exc.errors()}")
        return Error.process_validation_error(exc).to_resp()

    @app.exception_handler(Error)
    async def catch_custom_error(request: Request, error: Error) -> JSONResponse:
        logger.error(f"CustomError at {redact(str(request.url))}: {error!s}")
        return error.to_resp()

    @app.exception_handler(Exception)
    async def catch_exception(request: Request, error: Exception) -> JSONResponse:
        tb_str = "".join(traceback.format_exception(type(error), error, error.__traceback__))
        logger.error(f"FastAPIHandlerError at {redact(str(request.url))}:\n{tb_str}")
        gc.collect()
        return Error.process_exception(error).to_resp()


def error_api_responses() -> dict[int | str, dict[str, Any]]:
    return {
        http_status.HTTP_400_BAD_REQUEST: {
            "description": "Bad Request",
            "model": ErrorResponse,
        },
        http_status.HTTP_401_UNAUTHORIZED: {
            "description": "Unauthorized",
            "model": ErrorResponse,
        },
        http_status.HTTP_403_FORBIDDEN: {
            "description": "Forbidden",
            "model": ErrorResponse,
        },
        http_status.HTTP_404_NOT_FOUND: {
            "description": "Resource Not Found",
            "model": ErrorResponse,
        },
        http_status.HTTP_422_UNPROCESSABLE_ENTITY: {
            "description": "Validation Error",
            "model": ErrorResponse,
        },
        http_status.HTTP_500_INTERNAL_SERVER_ERROR: {
            "description": "Internal Server Error",
            "model": ErrorResponse,
        },
    }
