from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase


class PlaybackPosition(LinkBase):
    """Where one file was left in the player, so it resumes on any device (#96)."""

    file = fields.OneToOneField("model.DownloadFile", related_name="playback", on_delete=fields.CASCADE)
    position_seconds: float = fields.FloatField(default=0.0)
    duration_seconds: float = fields.FloatField(default=0.0)
    #: Played to within its last seconds, at least once.
    watched: bool = fields.BooleanField(default=False)

    if TYPE_CHECKING:
        file_id: UUID

    def __str__(self) -> str:
        return f"[PlaybackPosition: file {self.file_id}]"

    class Meta:
        table: ClassVar[str] = "playback_position"
        table_description: ClassVar[str] = "PlaybackPosition"
