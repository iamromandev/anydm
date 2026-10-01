from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class PlaybackPosition(LinkBase):
    """Where a download was left in the player, so it resumes on any device (#96)."""

    task = fields.ForeignKeyField(
        "model.Task", related_name="playback_positions", on_delete=fields.CASCADE
    )
    file_index: int = fields.IntField(default=0)
    position_seconds: float = fields.FloatField(default=0.0)
    duration_seconds: float = fields.FloatField(default=0.0)
    watched: bool = fields.BooleanField(default=False)

    def __str__(self) -> str:
        return f"[PlaybackPosition: task {self.task_id}, file_index {self.file_index}]"

    class Meta:
        table: ClassVar[str] = "playback_position"
        table_description: ClassVar[str] = "PlaybackPosition"
        ordering: ClassVar[list[str]] = ["file_index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("task", "file_index"),)
