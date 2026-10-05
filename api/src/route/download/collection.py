import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from fastapi.responses import Response

from src.core.success import Success
from src.core.type import Code
from src.data.schema.transfer import CollectionRequest, CollectionSchema, DownloadSchema
from src.service import CollectionService, get_collection_service

router = APIRouter()


@router.post(path="/collection", response_model=Success[CollectionSchema])
async def add_collection(
    payload: CollectionRequest,
    collections: Annotated[CollectionService, Depends(get_collection_service)],
) -> Response:
    """A playlist's or a channel tab's chosen videos, as one collection; the same listing again joins it."""
    return Success.created(data=await collections.add(payload)).to_resp()


@router.get(path="/collection/{collection_id}", response_model=Success[CollectionSchema])
async def get_collection(
    collection_id: uuid.UUID,
    collections: Annotated[CollectionService, Depends(get_collection_service)],
) -> Response:
    return Success.ok(data=await collections.get(collection_id)).to_resp()


@router.get(path="/collection/{collection_id}/downloads", response_model=Success[list[DownloadSchema]])
async def collection_downloads(
    collection_id: uuid.UUID,
    collections: Annotated[CollectionService, Depends(get_collection_service)],
    page: Annotated[int, Query(ge=1)] = 1,
    page_size: Annotated[int, Query(ge=1, le=100)] = 50,
) -> Response:
    """One page of a collection's videos, in listing order."""
    data, meta = await collections.downloads_page(collection_id, page, page_size)
    return Success.ok(data=data, meta=meta).to_resp()


@router.post(path="/collection/{collection_id}/pause", response_model=Success[CollectionSchema])
async def pause_collection(
    collection_id: uuid.UUID,
    collections: Annotated[CollectionService, Depends(get_collection_service)],
) -> Response:
    return Success.ok(data=await collections.pause(collection_id)).to_resp()


@router.post(path="/collection/{collection_id}/resume", response_model=Success[CollectionSchema])
async def resume_collection(
    collection_id: uuid.UUID,
    collections: Annotated[CollectionService, Depends(get_collection_service)],
) -> Response:
    return Success.ok(data=await collections.resume(collection_id)).to_resp()


@router.delete(path="/collection/{collection_id}")
async def cancel_collection(
    collection_id: uuid.UUID,
    collections: Annotated[CollectionService, Depends(get_collection_service)],
    delete_files: Annotated[bool, Query(description="Remove the collection's folder too.")] = True,
) -> Response:
    await collections.cancel(collection_id, delete_files=delete_files)
    return Success(code=Code.NO_CONTENT).to_resp()
