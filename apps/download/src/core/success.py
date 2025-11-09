from typing import Annotated, Any, Generic, TypeVar

from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from loguru import logger
from pydantic import Field

from .base import BaseSchema
from .format import utc_iso_timestamp
from .type import Code, Status

_T = TypeVar("_T")


class Meta(BaseSchema):
    page: Annotated[int, Field(default=1)] = 1
    page_size: Annotated[int, Field(default=10)] = 10
    total: Annotated[int, Field(default=100)] = 100
    total_pages: Annotated[int, Field(default=10)] = 10


class Success(BaseSchema, Generic[_T]):
    status: Annotated[Status, Field(...)] = Status.SUCCESS
    code: Annotated[Code, Field(...)] = Code.OK
    message: Annotated[str | None, Field(...)] = None
    data: Annotated[_T, Field(...)] = None
    meta: Annotated[Meta | None, Field(...)] = None
    timestamp: Annotated[str, Field(...)] = Field(default_factory=lambda: utc_iso_timestamp())

    def to_json(self, exclude_none: bool = True, log: bool = False) -> Any:
        json = jsonable_encoder(self, exclude_none=exclude_none)
        if log:
            logger.success(f"{self._tag}|to_json(): {json}")
        return json

    def to_resp(self, log: bool = False) -> JSONResponse:
        return JSONResponse(
            content=self.to_json(log=log),
            status_code=self.code.value,
        )

    @classmethod
    def ok(
        cls: type["Success"], message: str | None = None, data: Any = None, meta: Meta | None = None
    ) -> "Success":
        return cls(
            code=Code.OK,
            message=message,
            data=data,
            meta=meta
        )

    @classmethod
    def created(
        cls: type["Success"], message: str | None = None, data: Any = None, meta: Meta | None = None
    ) -> "Success":
        return cls(
            code=Code.CREATED,
            message=message,
            data=data,
            meta=meta
        )
