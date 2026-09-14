import uuid
from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.download import (
    TaskSchema,
    TorrentDownloadRequest,
    TorrentResolveRequest,
    TorrentResolveResponse,
)
from src.service import TorrentService, get_torrent_service

router = APIRouter()


# Every path here is a fixed segment under /download, so this router must be
# included BEFORE the download router: FastAPI matches in declaration order and
# /download/{task_id} would otherwise swallow /download/torrent.
@router.post(
    path="/download/torrent/resolve",
    response_model=Success[TorrentResolveResponse],
)
async def resolve_torrent(
    payload: TorrentResolveRequest,
    torrent_service: Annotated[TorrentService, Depends(get_torrent_service)],
) -> Response:
    """Inspect a magnet or .torrent without downloading it.

    A POST rather than a GET because the body can be a whole .torrent file, and
    because resolving is not free: it talks to peers.
    """
    data = await torrent_service.resolve(payload.torrent.strip())
    return Success.ok(data=data).to_resp()


@router.post(
    path="/download/torrent",
    response_model=Success[TaskSchema],
)
async def enqueue_torrent(
    payload: TorrentDownloadRequest,
    torrent_service: Annotated[TorrentService, Depends(get_torrent_service)],
) -> Response:
    data = await torrent_service.enqueue(payload.torrent.strip(), payload.files)
    return Success.created(data=data).to_resp()


@router.post(
    path="/download/{task_id}/seed/stop",
    response_model=Success[TaskSchema],
)
async def stop_seeding(
    task_id: uuid.UUID,
    torrent_service: Annotated[TorrentService, Depends(get_torrent_service)],
) -> Response:
    """Stop sharing a finished torrent, keeping its files."""
    return Success.ok(data=await torrent_service.stop_seeding(task_id)).to_resp()
