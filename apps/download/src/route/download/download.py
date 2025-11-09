from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import FileResponse
from pydantic import Field, HttpUrl

from src.service import DownloadService, get_download_service

router = APIRouter(prefix="/download", tags=["download"])


@router.get(path="")
async def download(
    request: Annotated[Request, Field(...)],
    service: Annotated[DownloadService, Depends(get_download_service)],
    url: Annotated[HttpUrl, Query(...)],
) -> FileResponse:
    data = await service.download(request, url)
    return data
