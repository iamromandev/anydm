import json
import uuid
from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from fastapi.responses import FileResponse, Response
from sse_starlette import EventSourceResponse

from src.core.success import Success
from src.core.type import Code
from src.data.schema.download import TaskSchema, UrlDownloadRequest, YoutubeDownloadRequest
from src.lib.event import EventHub, get_event_hub
from src.service import DownloadService, get_download_service

router = APIRouter()


# Paths carry the collection segment rather than the package router carrying a
# prefix: FastAPI refuses a route whose prefix and path are both empty.
#
# Declaration order matters. FastAPI matches in order, so every fixed segment
# under /download — /youtube, /url — must be declared before /download/{task_id},
# or the parameterised route swallows them.
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


@router.post(
    path="/download/url",
    response_model=Success[TaskSchema],
)
async def enqueue_url(
    payload: UrlDownloadRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    data = await download_service.enqueue_url(payload.url.strip())
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


@router.get(path="/download/events")
async def stream_events(
    download_service: Annotated[DownloadService, Depends(get_download_service)],
    hub: Annotated[EventHub, Depends(get_event_hub)],
) -> EventSourceResponse:
    """Live task updates.

    A browser reconnects on its own, so every connection opens with the full
    list before any incremental event — otherwise a client that reconnected
    mid-download would show nothing until the next progress tick.
    """
    subscription = hub.subscribe()
    tasks, _ = await download_service.list_tasks(page=1, page_size=200)

    async def publisher() -> AsyncIterator[dict[str, str]]:
        try:
            yield {"event": "tasks", "data": json.dumps([task.to_json() for task in tasks])}
            async for event, data in subscription:
                yield {"event": event, "data": json.dumps(data)}
        finally:
            subscription.close()

    # ``ping`` is sse-starlette's own comment heartbeat, which is what keeps a
    # proxy from reaping an idle connection.
    return EventSourceResponse(publisher(), ping=15)


@router.get(path="/download/{task_id}/file")
async def download_file(
    task_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> FileResponse:
    """Serve the finished file.

    ``FileResponse`` handles Range on its own, so a browser download that drops
    resumes instead of restarting.
    """
    path, filename, media_type = await download_service.resolve_file(task_id)
    return FileResponse(path=path, filename=filename, media_type=media_type)


@router.post(path="/download/{task_id}/pause", response_model=Success[TaskSchema])
async def pause_task(
    task_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    return Success.ok(data=await download_service.pause(task_id)).to_resp()


@router.post(path="/download/{task_id}/resume", response_model=Success[TaskSchema])
async def resume_task(
    task_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    return Success.ok(data=await download_service.resume(task_id)).to_resp()


@router.delete(path="/download/{task_id}")
async def cancel_task(
    task_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
    delete_files: Annotated[
        bool,
        Query(description="Remove the downloaded files too. Off keeps them and drops only the row."),
    ] = True,
) -> Response:
    # Defaulting to True keeps every existing caller doing what it did before
    # this parameter existed.
    await download_service.cancel(task_id, delete_files=delete_files)
    return Success(code=Code.NO_CONTENT).to_resp()


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
