from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class PlaybackPosition(LinkBase):
    """Where a download was left in the player, so it resumes on any device (#96).

    One row per task and file: ``file_index`` is a torrent's file, and 0 for a
    download's one file. Never NULL, because Postgres counts NULLs as distinct
    and the pair would stop being unique.

    ``watched`` outlives the position: stopping near the end clears where to
    resume, and the card still counts the file as seen.
    """

    # Not "positions": ``TaskSchema`` has a field of that name, and a relation
    # of the same name shadows it with Tortoise's manager (see ``File``).
    task = fields.ForeignKeyField(
        "model.Task", related_name="playback_positions", on_delete=fields.CASCADE
    )
    file_index: int = fields.IntField(default=0)
    position_seconds: float = fields.FloatField(default=0.0)
    duration_seconds: float = fields.FloatField(default=0.0)
    watched: bool = fields.BooleanField(default=False)

    class Meta:
        table: ClassVar[str] = "playback_position"
        table_description: ClassVar[str] = "PlaybackPosition"
        ordering: ClassVar[list[str]] = ["file_index"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("task", "file_index"),)
