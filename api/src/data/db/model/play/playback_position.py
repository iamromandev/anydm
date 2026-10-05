from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase


class PlaybackPosition(LinkBase):
    """Where one file was left in the player, so it resumes on any device (#96).

    One row per file for now. It points at the file rather than being one-to-one
    with it, so profiles (#421) can add a user to the key without reshaping File.
    """

    file = fields.ForeignKeyField("model.File", related_name="playback_positions", on_delete=fields.CASCADE)
    position_seconds: float = fields.FloatField(default=0.0)
    duration_seconds: float = fields.FloatField(default=0.0)
    watched: bool = fields.BooleanField(default=False)

    if TYPE_CHECKING:
        file_id: UUID

    def __str__(self) -> str:
        return f"[PlaybackPosition: file {self.file_id}, at {self.position_seconds}s]"

    class Meta:
        table: ClassVar[str] = "playback_position"
        table_description: ClassVar[str] = "PlaybackPosition"
        schema: ClassVar[str] = "play"
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("file",),)
