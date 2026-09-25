import json
import uuid
from collections.abc import AsyncIterator
from dataclasses import asdict
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from fastapi.responses import FileResponse, Response
from sse_starlette import EventSourceResponse

from src.core.success import Success
from src.core.type import Code
from src.data.schema.download import (
    BulkActionRequest,
    BulkResultSchema,
    MediaDownloadRequest,
    PositionRequest,
    PositionSchema,
    TaskSchema,
    TaskSummarySchema,
    UrlDownloadRequest,
)
from src.data.schema.stream import MediaInfoSchema
from src.data.type import TaskGroup, TaskSort
from src.lib.event import EventHub, get_event_hub
from src.service import (
    DiskGuard,
    DownloadService,
    StreamService,
    get_disk_guard,
    get_download_service,
    get_stream_service,
)

router = APIRouter()


# Paths carry the collection segment rather than the package router carrying a
# prefix: FastAPI refuses a route whose prefix and path are both empty.
#
# Declaration order matters. FastAPI matches in order, so every fixed segment
# under /download — /media, /youtube, /url — must be declared before /download/{task_id},
# or the parameterised route swallows them.
@router.post(
    path="/download/media",
    response_model=Success[TaskSchema],
)
async def enqueue_media(
    payload: MediaDownloadRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    """Queue a download from any page yt-dlp supports, for a quality preset."""
    data = await download_service.enqueue_media(payload.url.strip(), payload.preset)
    return Success.created(data=data).to_resp()


@router.post(
    path="/download/youtube",
    response_model=Success[TaskSchema],
    deprecated=True,
)
async def enqueue_youtube(
    payload: MediaDownloadRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    """Deprecated: ``POST /download/media``, under its old name. Removed in v0.4."""
    return await enqueue_media(payload, download_service)


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


@router.post(
    path="/download/bulk",
    response_model=Success[BulkResultSchema],
)
async def bulk_action(
    request: BulkActionRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    affected = await download_service.bulk(
        request.action, delete_files=request.delete_files
    )
    return Success.ok(data=BulkResultSchema(affected=affected)).to_resp()


@router.get(
    path="/download",
    response_model=Success[list[TaskSchema]],
)
async def list_tasks(
    download_service: Annotated[DownloadService, Depends(get_download_service)],
    page: Annotated[int, Query(ge=1)] = 1,
    page_size: Annotated[int, Query(ge=1, le=100)] = 50,
    group: Annotated[
        TaskGroup,
        Query(description="Which of the sidebar's filters to answer for"),
    ] = "all",
    sort: Annotated[
        TaskSort,
        Query(description="Field to order by; prefix with - for descending"),
    ] = "-created_at",
) -> Response:
    data, meta = await download_service.list_tasks(
        page=page, page_size=page_size, group=group, sort=sort
    )
    return Success.ok(data=data, meta=meta).to_resp()


@router.get(
    # Before "/download/{task_id}", per the note above: otherwise "summary" is
    # read as a task id and the request dies on a uuid it was never given.
    path="/download/summary",
    response_model=Success[TaskSummarySchema],
)
async def task_summary(
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    return Success.ok(data=await download_service.summary()).to_resp()


@router.get(path="/download/events")
async def stream_events(
    download_service: Annotated[DownloadService, Depends(get_download_service)],
    hub: Annotated[EventHub, Depends(get_event_hub)],
    disk: Annotated[DiskGuard, Depends(get_disk_guard)],
) -> EventSourceResponse:
    """Live task updates.

    A browser reconnects on its own, so every connection opens with the full
    list before any incremental event — otherwise a client that reconnected
    mid-download would show nothing until the next progress tick. The disk
    reading follows it for the same reason: the monitor only reports every 30 s.
    """
    subscription = hub.subscribe()
    tasks, _ = await download_service.list_tasks(page=1, page_size=200)
    usage = disk.usage()

    async def publisher() -> AsyncIterator[dict[str, str]]:
        try:
            yield {"event": "tasks", "data": json.dumps([task.to_json() for task in tasks])}
            if usage is not None:
                yield {"event": "disk", "data": json.dumps(asdict(usage))}
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


@router.put(path="/download/{task_id}/position", response_model=Success[PositionSchema])
async def save_position(
    task_id: uuid.UUID,
    payload: PositionRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    """Where a download was left in the player, so it resumes on any device (#96).

    Near the end, it's marked watched and its position cleared.
    """
    data = await download_service.save_position(
        task_id,
        payload.file_index,
        position_seconds=payload.position_seconds,
        duration_seconds=payload.duration_seconds,
    )
    return Success.ok(data=data).to_resp()


@router.get(path="/download/{task_id}/media", response_model=Success[MediaInfoSchema])
async def media_info(
    task_id: uuid.UUID,
    stream_service: Annotated[StreamService, Depends(get_stream_service)],
    file_index: Annotated[int | None, Query(ge=0)] = None,
) -> Response:
    """What the player needs to play a finished download (#94).

    Its type says whether the browser can play the file itself, from
    ``file_url``. When it can't, ``POST /stream/start`` with the task plays it
    through a session. A torrent's file is the one named, else its largest
    selected media file.
    """
    info = await stream_service.media_info(task_id, file_index)
    file_url = f"/download/{task_id}/file"
    if info.file_index is not None:
        file_url = f"{file_url}/{info.file_index}"
    return Success.ok(data=MediaInfoSchema(**asdict(info), file_url=file_url)).to_resp()


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
