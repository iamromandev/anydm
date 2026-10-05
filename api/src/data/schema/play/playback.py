"""Where a file was left in the player, as the API reads and writes it (#96)."""

from __future__ import annotations

from typing import Annotated

from pydantic import Field

from src.core.base import BaseSchema


class PlaybackSchema(BaseSchema):
    """Where one file was left in the player (#96)."""

    position_seconds: float = 0.0
    duration_seconds: float = 0.0
    #: Played to within its last seconds, at least once.
    watched: bool = False


class PlaybackRequest(BaseSchema):
    position_seconds: Annotated[float, Field(ge=0)]
    duration_seconds: Annotated[float, Field(ge=0)]
