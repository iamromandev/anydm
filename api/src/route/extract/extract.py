import json
from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from fastapi.responses import Response
from sse_starlette import EventSourceResponse

from src.core.success import Success
from src.data.schema.extract import ExtractRequest, ExtractSchema, PlaylistSchema
from src.service import ExtractService, ListingService, get_extract_service, get_listing_service
from src.service.extract.listing_service import LISTING_LIMIT

router = APIRouter()


# The path carries the collection segment rather than the package router
# carrying a prefix: FastAPI refuses a route whose prefix and path are both
# empty, which is what `prefix="/extract"` plus `path=""` produces.
@router.post(
    path="/extract",
    response_model=Success[ExtractSchema | PlaylistSchema],
)
async def extract(
    payload: ExtractRequest,
    extract_service: Annotated[ExtractService, Depends(get_extract_service)],
) -> Response:
    data: ExtractSchema | PlaylistSchema = await extract_service.extract(payload.url.strip())
    return Success.ok(data=data).to_resp()


@router.get(path="/extract/entries")
async def extract_entries(
    url: Annotated[str, Query(min_length=1, description="A playlist, or a channel's tab, as POST /extract named it")],
    listing_service: Annotated[ListingService, Depends(get_listing_service)],
    limit: Annotated[int, Query(ge=1, le=LISTING_LIMIT)] = LISTING_LIMIT,
) -> EventSourceResponse:
    """A playlist's videos as the site pages through them: ``entries`` frames, then ``done`` or ``failed``.

    ``failed`` rather than ``error``: an ``EventSource`` fires its own
    ``error`` on a dropped connection, and the page must tell the two apart.
    Nothing is kept here; a client that goes away stops the listing at its
    next video.
    """
    frames = listing_service.frames(url.strip(), limit=limit)

    async def publisher() -> AsyncIterator[dict[str, str]]:
        try:
            async for event, data in frames:
                yield {"event": event, "data": json.dumps(data)}
        finally:
            await frames.aclose()

    return EventSourceResponse(publisher(), ping=15)
