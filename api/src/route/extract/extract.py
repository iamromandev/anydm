from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.extract import ExtractRequest, ExtractSchema
from src.service import ExtractService, get_extract_service

router = APIRouter()


# The path carries the collection segment rather than the package router
# carrying a prefix: FastAPI refuses a route whose prefix and path are both
# empty, which is what `prefix="/extract"` plus `path=""` produces.
@router.post(
    path="/extract",
    response_model=Success[ExtractSchema],
)
async def extract(
    payload: ExtractRequest,
    extract_service: Annotated[ExtractService, Depends(get_extract_service)],
) -> Response:
    data: ExtractSchema = await extract_service.extract(payload.url.strip())
    return Success.ok(data=data).to_resp()
