from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query
from fastapi.exceptions import RequestValidationError
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.search import (
    BuiltinSourcePatch,
    BuiltinSourceSchema,
    BuiltinSourcesSchema,
    BuiltinTestRequest,
    BuiltinTestSchema,
    SearchSchema,
    SearchTorrentRequest,
    SearchTorrentSchema,
    SourcesSchema,
)
from src.service import SearchService, SourceSettingsService, get_search_service, get_source_settings_service

router = APIRouter()

Manage = Annotated[SourceSettingsService, Depends(get_source_settings_service)]

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


@router.post(path="/search/torrent", response_model=Success[SearchTorrentSchema])
async def search_torrent(
    payload: SearchTorrentRequest,
    search_service: Annotated[SearchService, Depends(get_search_service)],
) -> Response:
    """A result's .torrent fetched from its indexer (base64), or the magnet it redirects to."""
    return Success.ok(data=await search_service.fetch_torrent(payload.link.strip())).to_resp()


@router.get(path="/search/builtin", response_model=Success[BuiltinSourcesSchema])
async def list_builtin(manage: Manage) -> Response:
    """The built-in public sources, with whether each is on and the address it is asked at."""
    return Success.ok(data=await manage.list_sources()).to_resp()


@router.patch(path="/search/builtin/{name}", response_model=Success[BuiltinSourceSchema])
async def update_builtin(name: str, payload: BuiltinSourcePatch, manage: Manage) -> Response:
    """Turn a source on or off, or point it at another address. The choice is kept in the database."""
    return Success.ok(data=await manage.update(name, payload.enabled, payload.base_url)).to_resp()


@router.post(path="/search/builtin/{name}/reset", response_model=Success[BuiltinSourceSchema])
async def reset_builtin(name: str, manage: Manage) -> Response:
    """Put a source back to its built-in address and turn it on."""
    return Success.ok(data=await manage.reset(name)).to_resp()


@router.post(path="/search/builtin/{name}/test", response_model=Success[BuiltinTestSchema])
async def test_builtin(name: str, manage: Manage, payload: BuiltinTestRequest | None = None) -> Response:
    """Ask a source a small question and say whether it answered. Counts only, never a result's contents."""
    return Success.ok(data=await manage.test(name, payload.base_url if payload else None)).to_resp()
