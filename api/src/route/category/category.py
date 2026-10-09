import uuid
from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.transfer import (
    CategoryCreate,
    CategoryListSchema,
    CategoryOrderRequest,
    CategoryPatch,
    CategorySchema,
)
from src.service import CategoryService, get_category_service

router = APIRouter()

Manage = Annotated[CategoryService, Depends(get_category_service)]


# /category/order is declared before /category/{id}: FastAPI matches in declaration order.
@router.get(path="/category", response_model=Success[CategoryListSchema])
async def list_categories(manage: Manage) -> Response:
    """Every category by position, each with how many list items it holds."""
    return Success.ok(data=await manage.list()).to_resp()


@router.post(path="/category", response_model=Success[CategorySchema])
async def create_category(payload: CategoryCreate, manage: Manage) -> Response:
    """Add a category with a folder under DOWNLOAD_DIR. Answers 201."""
    return Success.created(data=await manage.create(payload.name, payload.folder)).to_resp()


@router.post(path="/category/order", response_model=Success[CategoryListSchema])
async def order_categories(payload: CategoryOrderRequest, manage: Manage) -> Response:
    """Put every category in the order given; the list must name each one once."""
    return Success.ok(data=await manage.order(payload.ids)).to_resp()


@router.patch(path="/category/{id}", response_model=Success[CategorySchema])
async def update_category(id: uuid.UUID, payload: CategoryPatch, manage: Manage) -> Response:
    """Rename a category or point it at another folder. Downloads already placed stay where they are."""
    return Success.ok(data=await manage.update(id, payload.name, payload.folder)).to_resp()


@router.delete(path="/category/{id}")
async def delete_category(id: uuid.UUID, manage: Manage) -> Response:
    """Delete an empty category. Downloads is refused, and so is one still in use. Answers 204."""
    await manage.delete(id)
    return Success.no_content().to_resp()
