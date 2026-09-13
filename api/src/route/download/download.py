import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.download import TaskSchema, YoutubeDownloadRequest
from src.service import DownloadService, get_download_service

router = APIRouter()


# Paths carry the collection segment rather than the package router carrying a
# prefix: FastAPI refuses a route whose prefix and path are both empty.
@router.post(
    path="/download/youtube",
    response_model=Success[TaskSchema],
)
async def enqueue_youtube(
    payload: YoutubeDownloadRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    data = await download_service.enqueue_youtube(payload.url.strip(), payload.preset)
    return Success.created(data=data).to_resp()


@router.get(
    path="/download",
    response_model=Success[list[TaskSchema]],
)
async def list_tasks(
    download_service: Annotated[DownloadService, Depends(get_download_service)],
    page: Annotated[int, Query(ge=1)] = 1,
    page_size: Annotated[int, Query(ge=1, le=100)] = 50,
) -> Response:
    data, meta = await download_service.list_tasks(page=page, page_size=page_size)
    return Success.ok(data=data, meta=meta).to_resp()


@router.get(
    path="/download/{task_id}",
    response_model=Success[TaskSchema],
)
async def get_task(
    task_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    data = await download_service.get_task(task_id)
    return Success.ok(data=data).to_resp()
