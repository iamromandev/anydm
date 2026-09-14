from __future__ import annotations

from typing import Annotated, Any

from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse, Response
from loguru import logger
from pydantic import Field

from src.core.base import BaseSchema
from src.core.format import utc_iso_timestamp
from src.core.type import Code, Status


class Meta(BaseSchema):
    page: Annotated[int, Field(default=1)]
    page_size: Annotated[int, Field(default=10)]
    total: Annotated[int, Field(default=100)]
    total_pages: Annotated[int, Field(default=10)]


class Success[T](BaseSchema):
    status: Status = Status.SUCCESS
    code: Code = Code.OK
    message: str | None = None
    data: T | None = None
    meta: Meta | None = None
    timestamp: str = Field(default_factory=utc_iso_timestamp)

    def to_json(self, exclude_none: bool = True, log: bool = False) -> Any:
        json = jsonable_encoder(self, exclude_none=exclude_none)
        if log:
            logger.success(f"{self._tag}|to_json(): {json}")
        return json

    def to_resp(self, exclude_none: bool = True, log: bool = False) -> Response:
        status_code = int(self.code)
        # 204 and 304 carry no body — the two statuses Starlette itself refuses
        # to frame one for. Uvicorn then expects zero body bytes, and a JSON
        # envelope sent after the headers raises "Response content longer than
        # Content-Length" and closes the connection under the client, which
        # only notices on its next keep-alive request. The envelope is dropped
        # rather than serialised; a 204 has nothing to say.
        if status_code in (204, 304):
            return Response(status_code=status_code)
        return JSONResponse(
            content=self.to_json(exclude_none=exclude_none, log=log),
            status_code=status_code,
        )

    @classmethod
    def new(
        cls,
        code: Code = Code.OK,
        message: str | None = None,
        data: Any = None,
        meta: Meta | None = None,
    ) -> Success[Any]:
        return cls(
            code=code,
            message=message,
            data=data,
            meta=meta,
        )

    @classmethod
    def ok(
        cls,
        message: str | None = None,
        data: Any = None,
        meta: Meta | None = None,
    ) -> Success[Any]:
        return cls(
            code=Code.OK,
            message=message,
            data=data,
            meta=meta,
        )

    @classmethod
    def created(
        cls,
        message: str | None = None,
        data: Any = None,
        meta: Meta | None = None,
    ) -> Success[Any]:
        return cls(
            code=Code.CREATED,
            message=message,
            data=data,
            meta=meta,
        )

    @classmethod
    def no_content(
        cls,
        message: str | None = None,
        data: Any = None,
        meta: Meta | None = None,
    ) -> Success[Any]:
        return cls(
            code=Code.NO_CONTENT,
            message=message,
            data=data,
            meta=meta,
        )
