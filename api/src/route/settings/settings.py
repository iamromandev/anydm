from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.settings import ServerSettingsSchema
from src.service import SettingsService, get_settings_service

router = APIRouter()


@router.get(
    path="/settings",
    response_model=Success[ServerSettingsSchema],
)
async def read_settings(
    settings_service: Annotated[SettingsService, Depends(get_settings_service)],
) -> Response:
    """How this API is configured, minus anything nobody should see.

    Read-only. Changing a setting means changing ``api/.env`` and restarting,
    which is deliberate: several of these are read once at startup.
    """
    return Success.ok(data=settings_service.describe()).to_resp()
