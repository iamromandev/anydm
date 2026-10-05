import uuid
from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.catalog import (
    SourceCreate,
    SourceListSchema,
    SourcePatch,
    SourceProbeRequest,
    SourceSchema,
    SourceTestRequest,
    SourceTestSchema,
)
from src.service import SourceService, get_source_service

router = APIRouter()

Manage = Annotated[SourceService, Depends(get_source_service)]


@router.get(path="/source", response_model=Success[SourceListSchema])
async def list_sources(manage: Manage) -> Response:
    """Every source: registry rows first in registry order, then created ones oldest-first."""
    return Success.ok(data=await manage.list()).to_resp()


@router.post(path="/source", response_model=Success[SourceSchema])
async def create_source(payload: SourceCreate, manage: Manage) -> Response:
    """Add a source: a Torznab indexer, or another row of a built-in kind. Answers 201."""
    return Success.created(
        data=await manage.create(payload.name, payload.kind, payload.base_url, payload.api_key, payload.enabled)
    ).to_resp()


@router.patch(path="/source/{id}", response_model=Success[SourceSchema])
async def update_source(id: uuid.UUID, payload: SourcePatch, manage: Manage) -> Response:
    """Turn a source on or off, point it at another address, or set, replace or clear its key."""
    return Success.ok(data=await manage.update(id, payload.enabled, payload.base_url, payload.api_key)).to_resp()


@router.delete(path="/source/{id}")
async def delete_source(id: uuid.UUID, manage: Manage) -> Response:
    """Delete a created source. Registry rows are refused: switch one off instead. Answers 204."""
    await manage.delete(id)
    return Success.no_content().to_resp()


@router.post(path="/source/{id}/reset", response_model=Success[SourceSchema])
async def reset_source(id: uuid.UUID, manage: Manage) -> Response:
    """Put a built-in-kind source back to its registry address and state."""
    return Success.ok(data=await manage.reset(id)).to_resp()


@router.post(path="/source/{id}/test", response_model=Success[SourceTestSchema])
async def test_source(id: uuid.UUID, manage: Manage, payload: SourceTestRequest | None = None) -> Response:
    """Ask a source a small question and say whether it answered, optionally at unsaved values."""
    return Success.ok(data=await manage.test(id, payload.base_url if payload else None, payload.api_key if payload else None)).to_resp()


@router.post(path="/source/test", response_model=Success[SourceTestSchema])
async def probe_source(payload: SourceProbeRequest, manage: Manage) -> Response:
    """Ask an unsaved source whether it answers, before adding it. Counts only, never a result's contents."""
    return Success.ok(data=await manage.probe(payload.kind, payload.base_url, payload.api_key)).to_resp()
