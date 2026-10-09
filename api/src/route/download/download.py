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
from src.data.schema.play import MediaInfoSchema, PlaybackRequest, PlaybackSchema
from src.data.schema.transfer import (
    BatchItemSchema,
    BatchLinks,
    BatchPreviewSchema,
    BatchRequest,
    BulkActionRequest,
    BulkResultSchema,
    CategoryMoveRequest,
    CollectionSchema,
    DownloadSchema,
    DownloadSummarySchema,
    MediaDownloadRequest,
    UrlDownloadRequest,
)
from src.data.type import DownloadGroup, DownloadSort
from src.lib.event import EventHub, get_event_hub
from src.service import (
    CategoryMover,
    DiskGuard,
    DownloadService,
    StreamService,
    get_category_mover,
    get_disk_guard,
    get_download_service,
    get_stream_service,
)

router = APIRouter()


# Paths carry the collection segment rather than the package router carrying a
# prefix: FastAPI refuses a route whose prefix and path are both empty.
#
# Declaration order matters. FastAPI matches in order, so every fixed segment
# under /download — /media, /url, /bulk, /summary, /events — must be declared
# before /download/{download_id}, or the parameterised route swallows them.
@router.post(path="/download/media", response_model=Success[DownloadSchema])
async def enqueue_media(
    payload: MediaDownloadRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    """Queue a download from any page yt-dlp supports, for a quality preset.

    409 when the list already holds the page, naming that download; ``allow_duplicate`` adds it anyway.
    """
    data = await download_service.enqueue_media(
        payload.url.strip(), payload.preset, allow_duplicate=payload.allow_duplicate
    )
    return Success.created(data=data).to_resp()


@router.post(path="/download/url", response_model=Success[DownloadSchema])
async def enqueue_url(
    payload: UrlDownloadRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    """Queue a direct download.

    409 when the list already holds the address, naming that download; ``allow_duplicate`` adds it anyway.
    """
    data = await download_service.enqueue_url(payload.url.strip(), allow_duplicate=payload.allow_duplicate)
    return Success.created(data=data).to_resp()


@router.post(path="/download/batch/preview", response_model=Success[BatchPreviewSchema])
async def preview_batch(
    payload: BatchLinks,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    """The links a list or a pattern such as ``img[001-120].png`` names, without adding any.

    400 for a pattern that cannot be expanded and for more than 1,000 links.
    """
    return Success.ok(data=download_service.preview_batch(payload.lines, payload.pattern)).to_resp()


@router.post(path="/download/batch", response_model=Success[list[BatchItemSchema]])
async def add_batch(
    payload: BatchRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    """Add many links, each as the add box would, and answer for every one.

    ``added``, ``duplicate`` (naming the download that already has it) or ``error``;
    one failing link never stops the rest. The answer is 200 whatever the mix.
    """
    data = await download_service.add_batch(
        payload.lines, payload.pattern, payload.preset, allow_duplicate=payload.allow_duplicate
    )
    return Success.ok(data=data).to_resp()


@router.post(path="/download/bulk", response_model=Success[BulkResultSchema])
async def bulk_action(
    request: BulkActionRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    affected = await download_service.bulk(request.action, delete_files=request.delete_files)
    return Success.ok(data=BulkResultSchema(affected=affected)).to_resp()


@router.get(path="/download", response_model=Success[list[DownloadSchema | CollectionSchema]])
async def list_items(
    download_service: Annotated[DownloadService, Depends(get_download_service)],
    page: Annotated[int, Query(ge=1)] = 1,
    page_size: Annotated[int, Query(ge=1, le=100)] = 50,
    group: Annotated[DownloadGroup, Query(description="Which of the sidebar's filters to answer for")] = "all",
    sort: Annotated[DownloadSort, Query(description="Field to order by; prefix with - for descending")] = "-created_at",
    category: Annotated[uuid.UUID | None, Query(description="Only the items in this category")] = None,
) -> Response:
    """Standalone downloads and collections as one list, each item tagged by ``type``."""
    data, meta = await download_service.list_items(
        page=page, page_size=page_size, group=group, sort=sort, category=category
    )
    return Success.ok(data=data, meta=meta).to_resp()


@router.get(path="/download/summary", response_model=Success[DownloadSummarySchema])
async def download_summary(
    download_service: Annotated[DownloadService, Depends(get_download_service)],
    category: Annotated[uuid.UUID | None, Query(description="Count only the items in this category")] = None,
) -> Response:
    return Success.ok(data=await download_service.summary(category)).to_resp()


@router.get(path="/download/events")
async def stream_events(
    download_service: Annotated[DownloadService, Depends(get_download_service)],
    hub: Annotated[EventHub, Depends(get_event_hub)],
    disk: Annotated[DiskGuard, Depends(get_disk_guard)],
) -> EventSourceResponse:
    """Live updates: ``downloads`` first, then ``download``, ``collection``, ``progress`` and ``disk``.

    A browser reconnects on its own, so every connection opens with the full
    list before any incremental event — otherwise a client that reconnected
    mid-download would show nothing until the next progress tick. The disk
    reading follows it for the same reason: the monitor only reports every 30 s.
    """
    subscription = hub.subscribe()
    items, _ = await download_service.list_items(page=1, page_size=200)
    usage = disk.usage()

    async def publisher() -> AsyncIterator[dict[str, str]]:
        try:
            yield {"event": "downloads", "data": json.dumps([item.to_json() for item in items])}
            if usage is not None:
                yield {"event": "disk", "data": json.dumps(asdict(usage))}
            async for event, data in subscription:
                yield {"event": event, "data": json.dumps(data)}
        finally:
            subscription.close()

    # ``ping`` is sse-starlette's own comment heartbeat, which is what keeps a
    # proxy from reaping an idle connection.
    return EventSourceResponse(publisher(), ping=15)


@router.get(path="/download/{download_id}/file/{file_index}")
async def download_file(
    download_id: uuid.UUID,
    file_index: int,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> FileResponse:
    """Serve a finished file: a torrent's by index, any other download's one file at 0.

    ``FileResponse`` handles Range on its own, so a browser download that drops
    resumes instead of restarting, and a player can seek.
    """
    path, filename, media_type = await download_service.resolve_file(download_id, file_index)
    return FileResponse(path=path, filename=filename, media_type=media_type)


@router.put(path="/download/{download_id}/file/{file_index}/playback", response_model=Success[PlaybackSchema])
async def save_playback(
    download_id: uuid.UUID,
    file_index: int,
    payload: PlaybackRequest,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    """Where a file was left in the player, so it resumes on any device (#96).

    Near the end, it's marked watched and its position cleared.
    """
    data = await download_service.save_playback(
        download_id,
        file_index,
        position_seconds=payload.position_seconds,
        duration_seconds=payload.duration_seconds,
    )
    return Success.ok(data=data).to_resp()


@router.get(path="/download/{download_id}/media", response_model=Success[MediaInfoSchema])
async def media_info(
    download_id: uuid.UUID,
    stream_service: Annotated[StreamService, Depends(get_stream_service)],
    file_index: Annotated[int | None, Query(ge=0)] = None,
) -> Response:
    """What the player needs to play a finished download (#94).

    Its type says whether the browser can play the file itself, from
    ``file_url``. When it can't, ``POST /stream/start`` with the download plays
    it through a session. A torrent's file is the one named, else its largest
    selected media file.
    """
    info = await stream_service.media_info(download_id, file_index)
    file_url = f"/download/{download_id}/file/{info.file_index or 0}"
    return Success.ok(data=MediaInfoSchema(**asdict(info), file_url=file_url)).to_resp()


@router.get(path="/download/{download_id}/subtitles/{track}.vtt")
async def subtitle_file(
    download_id: uuid.UUID,
    track: int,
    stream_service: Annotated[StreamService, Depends(get_stream_service)],
    file_index: Annotated[int | None, Query(ge=0)] = None,
) -> FileResponse:
    """A subtitle track of a finished download, whole, as WebVTT (#100).

    For a file the browser plays itself: a session serves its cues by the
    segment instead. Extracted once, then kept.
    """
    path = await stream_service.subtitle_file(download_id, file_index, track)
    return FileResponse(path=path, media_type="text/vtt")


@router.post(path="/download/{download_id}/pause", response_model=Success[DownloadSchema])
async def pause_download(
    download_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    return Success.ok(data=await download_service.pause(download_id)).to_resp()


@router.post(path="/download/{download_id}/resume", response_model=Success[DownloadSchema])
async def resume_download(
    download_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    return Success.ok(data=await download_service.resume(download_id)).to_resp()


@router.put(path="/download/{download_id}/category", response_model=Success[DownloadSchema])
async def move_download(
    download_id: uuid.UUID,
    payload: CategoryMoveRequest,
    mover: Annotated[CategoryMover, Depends(get_category_mover)],
) -> Response:
    """Move a download to another category, its finished file and subtitles with it.

    409 while it downloads or muxes; 422 for a torrent (it stays where it was added) and for a collection's video.
    """
    return Success.ok(data=await mover.move_download(download_id, payload.category_id)).to_resp()


@router.delete(path="/download/{download_id}")
async def cancel_download(
    download_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
    delete_files: Annotated[
        bool,
        Query(description="Remove the downloaded files too. Off keeps them and drops only the row."),
    ] = True,
) -> Response:
    await download_service.cancel(download_id, delete_files=delete_files)
    return Success(code=Code.NO_CONTENT).to_resp()


@router.get(path="/download/{download_id}", response_model=Success[DownloadSchema])
async def get_download(
    download_id: uuid.UUID,
    download_service: Annotated[DownloadService, Depends(get_download_service)],
) -> Response:
    return Success.ok(data=await download_service.get(download_id)).to_resp()
