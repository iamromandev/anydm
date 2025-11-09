from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from src.core.success import Success
from src.schemas.health import HealthSchema
from src.services import HealthService, get_health_service

router = APIRouter(prefix="/health", tags=["health"])


@router.get(path="/check")
async def check(
    health_service: Annotated[HealthService, Depends(get_health_service)]
) -> JSONResponse:
    output: HealthSchema = await health_service.check_health()
    return Success.ok(data=output).to_resp()
