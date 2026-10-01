from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query
from fastapi.exceptions import RequestValidationError
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.search import (
    SearchSchema,
    SearchTorrentRequest,
    SearchTorrentSchema,
    SearchVideosSchema,
    SourcesSchema,
)
from src.service import (
    SearchService,
    VideoSearchService,
    get_search_service,
    get_video_search_service,
)

router = APIRouter()

Category = Literal["all", "movies", "tv", "music", "software", "books", "other"]


@router.get(path="/search/sources", response_model=Success[SourcesSchema])
async def search_sources(search_service: Annotated[SearchService, Depends(get_search_service)]) -> Response:
    """Whether search is on, and the indexers' names; never their URLs or keys."""
    return Success.ok(data=await search_service.sources()).to_resp()


@router.get(path="/search", response_model=Success[SearchSchema])
async def search(
    search_service: Annotated[SearchService, Depends(get_search_service)],
    q: Annotated[str, Query(max_length=400, description="What to look for, 2 to 200 characters once trimmed; empty browses the latest")] = "",
    category: Annotated[Category, Query()] = "all",
    fresh: Annotated[bool, Query(description="Browse only: skip the cache")] = False,
) -> Response:
    query = q.strip()
    if query and not 2 <= len(query) <= 200:
        # FastAPI's own 422, for a query only trimming shows to be too short or too long.
        raise RequestValidationError([{"loc": ("query", "q"), "msg": "q must be 2 to 200 characters", "type": "value_error"}])
    return Success.ok(data=await search_service.search(query, category, fresh)).to_resp()


@router.get(path="/search/youtube", response_model=Success[SearchVideosSchema])
async def search_youtube(
    video_search_service: Annotated[VideoSearchService, Depends(get_video_search_service)],
    q: Annotated[str, Query(max_length=400, description="What to look for, 2 to 200 characters once trimmed")],
    limit: Annotated[int, Query(ge=1, le=30, description="How many videos to ask for")] = 20,
) -> Response:
    """YouTube's videos for some words; Add sends a result's ``url`` to the normal download flow."""
    query = q.strip()
    if not 2 <= len(query) <= 200:
        raise RequestValidationError([{"loc": ("query", "q"), "msg": "q must be 2 to 200 characters", "type": "value_error"}])
    return Success.ok(data=await video_search_service.search(query, limit)).to_resp()


@router.post(path="/search/torrent", response_model=Success[SearchTorrentSchema])
async def search_torrent(
    payload: SearchTorrentRequest,
    search_service: Annotated[SearchService, Depends(get_search_service)],
) -> Response:
    """A result's .torrent fetched from its indexer (base64), or the magnet it redirects to."""
    return Success.ok(data=await search_service.fetch_torrent(payload.link.strip())).to_resp()
