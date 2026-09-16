from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import FileResponse, Response

from src.core.error import Error
from src.core.success import Success
from src.core.type import Code
from src.data.schema.stream import StreamSessionSchema, StreamStartRequest
from src.service import StreamService, get_stream_service

router = APIRouter()


@router.post(
    path="/stream/start",
    response_model=Success[StreamSessionSchema],
)
async def start_stream(
    payload: StreamStartRequest,
    stream_service: Annotated[StreamService, Depends(get_stream_service)],
) -> Response:
    if payload.torrent:
        session = await stream_service.start_torrent_session(payload.torrent.strip())
    elif payload.url:
        session = await stream_service.start_session(payload.url.strip())
    else:
        raise Error.bad_request("Provide either url or torrent")
    schema = StreamSessionSchema(
        session_id=session.id,
        playlist_url=f"/stream/{session.id}/playlist.m3u8",
        duration_seconds=session.duration_seconds,
        has_video=session.has_video,
    )
    return Success.created(data=schema).to_resp()


@router.get(path="/stream/{session_id}/playlist.m3u8")
async def get_playlist(
    session_id: str,
    stream_service: Annotated[StreamService, Depends(get_stream_service)],
) -> Response:
    session = stream_service.get_session(session_id)
    return Response(
        content=stream_service.playlist_text(session),
        media_type="application/vnd.apple.mpegurl",
    )


@router.get(path="/stream/{session_id}/segment_{index}.ts")
async def get_segment(
    session_id: str,
    index: int,
    stream_service: Annotated[StreamService, Depends(get_stream_service)],
) -> FileResponse:
    session = stream_service.get_session(session_id)
    path = await stream_service.get_segment(session, index)
    return FileResponse(path=path, media_type="video/mp2t")


@router.delete(path="/stream/{session_id}")
async def stop_stream(
    session_id: str,
    stream_service: Annotated[StreamService, Depends(get_stream_service)],
) -> Response:
    await stream_service.stop_session(session_id)
    return Success(code=Code.NO_CONTENT).to_resp()
