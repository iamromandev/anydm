from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.download import TorrentResolveRequest, TorrentResolveResponse
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
