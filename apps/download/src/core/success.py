from dataclasses import dataclass, field
from typing import Annotated, Any

from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from loguru import logger
from pydantic import Field

from .base import BaseSchema
from .formats import utc_iso_timestamp
from .mixins import BaseMixin
from .type import Code, Status


class Meta(BaseSchema):
    page: Annotated[int, Field(default=1)] = 1
    page_size: Annotated[int, Field(default=10)] = 10
    total: Annotated[int, Field(default=100)] = 100
    total_pages: Annotated[int, Field(default=10)] = 10


@dataclass
class Success(BaseMixin):
    status: Annotated[Status, Field(...)] = Status.SUCCESS
    code: Annotated[Code, Field(...)] = Code.OK
    message: Annotated[str | None, Field(...)] = None
    data: Annotated[Any, Field(...)] = None
    meta: Annotated[Meta | None, Field(...)] = None
    timestamp: Annotated[str, Field(...)] = field(default_factory=lambda: utc_iso_timestamp())

    def to_json(self, exclude_none: bool = True) -> Any:
        json = jsonable_encoder(self, exclude_none=exclude_none)
        logger.info(f"{self._tag}|to_json(): {json}")
        return json

    def to_resp(self) -> JSONResponse:
        return JSONResponse(
            content=self.to_json(),
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