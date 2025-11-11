from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import FileResponse
from pydantic import Field, HttpUrl
from starlette.responses import JSONResponse

from src.core.success import Success
from src.service import DownloadService, get_download_service

router = APIRouter(prefix="/download", tags=["download"])


@router.get(path="/meta")
async def meta(
    request: Annotated[Request, Field(...)],
    service: Annotated[DownloadService, Depends(get_download_service)],
    url: Annotated[HttpUrl, Query(...)],
) -> JSONResponse:
    data = await service.extract_meta(request, url)
    return Success.ok(data=data).to_resp()

@router.get(path="")
async def download(
    request: Annotated[Request, Field(...)],
    service: Annotated[DownloadService, Depends(get_download_service)],
    url: Annotated[HttpUrl, Query(...)],
) -> FileResponse:
    data = await service.download(request, url)
    return data
