from dataclasses import asdict
from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import Response

from src.core.error import Error
from src.core.success import Success
from src.data.schema.system import DiskSchema
from src.service import DiskGuard, get_disk_guard

router = APIRouter()


@router.get(
    path="/system/disk",
    response_model=Success[DiskSchema],
)
async def read_disk(
    guard: Annotated[DiskGuard, Depends(get_disk_guard)],
) -> Response:
    """Free space where downloads land.

    The UI does not call this; it gets the same numbers as ``disk`` frames on
    ``/download/events``. This is for scripts and for checking by hand.
    """
    usage = guard.usage()
    if usage is None:
        raise Error.service_unavailable(message="Cannot read the download directory's disk usage")
    return Success.ok(data=DiskSchema(**asdict(usage))).to_resp()
