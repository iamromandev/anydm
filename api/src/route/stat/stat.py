from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from fastapi.responses import Response

from src.core.success import Success
from src.data.schema.audit import StatsSchema
from src.data.type import StatGroup
from src.service import StatService, get_stat_service

router = APIRouter()

Stats = Annotated[StatService, Depends(get_stat_service)]


@router.get(path="/stats", response_model=Success[StatsSchema])
async def get_stats(
    stats: Stats,
    start: Annotated[date | None, Query(alias="from")] = None,
    end: Annotated[date | None, Query(alias="to")] = None,
    group: StatGroup = StatGroup.DAY,
) -> Response:
    """What was downloaded and uploaded from ``from`` to ``to`` (UTC days, both included; the last 30 days when left
    out), added up by ``group``: ``day``, ``kind`` of source, or ``provider``. Ranges are at most 366 days."""
    return Success.ok(data=await stats.stats(start, end, group)).to_resp()
