from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query
from fastapi.exceptions import RequestValidationError
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.search import SearchSchema, SearchTorrentRequest, SearchTorrentSchema, SourcesSchema
from src.service import SearchService, get_search_service

router = APIRouter()

Category = Literal["all", "movies", "tv", "music", "software", "books", "other"]


@router.get(path="/search/sources", response_model=Success[SourcesSchema])
async def search_sources(search_service: Annotated[SearchService, Depends(get_search_service)]) -> Response:
    """Whether search is on, and the indexers' names; never their URLs or keys."""
    return Success.ok(data=search_service.sources()).to_resp()


@router.get(path="/search", response_model=Success[SearchSchema])
async def search(
    q: Annotated[str, Query(min_length=1, max_length=400, description="What to look for, 2 to 200 characters once trimmed")],
    search_service: Annotated[SearchService, Depends(get_search_service)],
    category: Annotated[Category, Query()] = "all",
) -> Response:
    query = q.strip()
    if not 2 <= len(query) <= 200:
        # FastAPI's own 422, for a query only trimming shows to be too short or too long.
        raise RequestValidationError([{"loc": ("query", "q"), "msg": "q must be 2 to 200 characters", "type": "value_error"}])
    return Success.ok(data=await search_service.search(query, category)).to_resp()


@router.post(path="/search/torrent", response_model=Success[SearchTorrentSchema])
async def search_torrent(
    payload: SearchTorrentRequest,
    search_service: Annotated[SearchService, Depends(get_search_service)],
) -> Response:
    """A result's .torrent fetched from its indexer (base64), or the magnet it redirects to."""
    return Success.ok(data=await search_service.fetch_torrent(payload.link.strip())).to_resp()
