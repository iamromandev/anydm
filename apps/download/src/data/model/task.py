from typing import Any

from tortoise import fields

from src.core.base import Base
from src.core.type import Action, State


class Task(Base):
    state: State = fields.CharEnumField(State, default=State.NEW)
    action: Action = fields.CharEnumField(Action, default=Action.EXTRACT)
    input: dict[str, Any] | list[None] | None = fields.JSONField(null=True, default=None)
    output: dict[str, Any] | list[None] | None = fields.JSONField(null=True, default=None)
    meta: dict[str, Any] | list[None] | None = fields.JSONField(null=True, default=None)

    class Meta:
        ordering = ["action"]
        table = "task"
        table_description = "Task"