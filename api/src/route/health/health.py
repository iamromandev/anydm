from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.health import HealthSchema
from src.service import HealthService, get_health_service

router = APIRouter()


@router.get(
    path="/check",
    response_model=Success[HealthSchema],
)
async def check(
    request: Request, health_service: Annotated[HealthService, Depends(get_health_service)]
) -> Response:
    # ``scope["server"]`` is the ``(host, port)`` this request was received on,
    # so the reported address is always the real, dynamically-bound one.
    server = request.scope.get("server")
    data: HealthSchema = await health_service.check_health(
        host=server[0] if server else None,
        port=server[1] if server else None,
    )
    return Success.ok(data=data).to_resp()
