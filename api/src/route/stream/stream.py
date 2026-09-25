import json
from collections.abc import AsyncIterator
from dataclasses import asdict
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import FileResponse, Response
from sse_starlette import EventSourceResponse

from src.core.auth import query_key, with_segment_key
from src.core.error import Error
from src.core.success import Success
from src.core.type import Code
from src.data.schema.stream import AudioSwitchRequest, AudioTrackSchema, StreamSessionSchema, StreamStartRequest
from src.lib.event import EventHub, get_event_hub
from src.service import StreamService, get_stream_service
from src.service.stream.session import StreamSession

router = APIRouter()


@router.post(
    path="/stream/start",
    response_model=Success[StreamSessionSchema],
)
async def start_stream(
    payload: StreamStartRequest,
    stream_service: Annotated[StreamService, Depends(get_stream_service)],
) -> Response:
    # The request allows exactly one of the three (``StreamStartRequest``).
    audio = {"audio_language": payload.audio_language, "audio_track": payload.audio_track}
    if payload.task_id is not None:
        session = await stream_service.start_task_session(payload.task_id, payload.file_index, **audio)
    elif payload.torrent:
        session = await stream_service.start_torrent_session(payload.torrent.strip(), payload.file_index, **audio)
    elif payload.url:
        session = await stream_service.start_session(payload.url.strip(), **audio)
    else:
        raise Error.bad_request("Provide exactly one of url, torrent or task_id")
    return Success.created(data=_session_schema(session)).to_resp()


@router.post(
    path="/stream/{session_id}/audio",
    response_model=Success[StreamSessionSchema],
)
async def switch_audio(
    session_id: str,
    payload: AudioSwitchRequest,
    stream_service: Annotated[StreamService, Depends(get_stream_service)],
) -> Response:
    """A new session playing another audio track, from where this one is (#99).

    This one keeps playing: the player stops it once the new one is ready.
    """
    session = stream_service.get_session(session_id)
    switched = await stream_service.switch_audio(session, payload.track)
    return Success.created(data=_session_schema(switched)).to_resp()


def _session_schema(session: StreamSession) -> StreamSessionSchema:
    ready = session.status == "ready"
    return StreamSessionSchema(
        session_id=session.id,
        playlist_url=f"/stream/{session.id}/playlist.m3u8",
        status=session.status,
        duration_seconds=session.duration_seconds if ready else None,
        has_video=session.has_video if ready else None,
        audio_tracks=[AudioTrackSchema(**asdict(track)) for track in session.audio_tracks] if ready else None,
        audio_track=session.audio_track if ready else None,
    )


@router.get(path="/stream/events")
async def stream_events(hub: Annotated[EventHub, Depends(get_event_hub)]) -> EventSourceResponse:
    """Live status for in-flight torrent stream sessions.

    One shared stream for every session, same as ``/download/events`` —
    consumers filter by the ``id`` field in each event's payload.
    """
    subscription = hub.subscribe()

    async def publisher() -> AsyncIterator[dict[str, str]]:
        try:
            async for event, data in subscription:
                yield {"event": event, "data": json.dumps(data)}
        finally:
            subscription.close()

    return EventSourceResponse(publisher(), ping=15)


@router.get(path="/stream/{session_id}/playlist.m3u8")
async def get_playlist(
    request: Request,
    session_id: str,
    stream_service: Annotated[StreamService, Depends(get_stream_service)],
) -> Response:
    session = stream_service.get_session(session_id)
    text = stream_service.playlist_text(session)
    # A player that could only put the key in this URL fetches the segments
    # the same way, by the relative URIs below, which carry no query. hls.js
    # sends a header instead and gets the playlist untouched.
    if key := query_key(request):
        text = with_segment_key(text, key)
    return Response(content=text, media_type="application/vnd.apple.mpegurl")


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
