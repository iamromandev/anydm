from __future__ import annotations

from typing import TYPE_CHECKING, Any, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import PreferenceKey


class Preference(LinkBase):
    """One setting of one person: a known key and a JSON value.
    """

    user = fields.ForeignKeyField("model.User", related_name="preferences", on_delete=fields.CASCADE)
    key: PreferenceKey = fields.CharEnumField(PreferenceKey, max_length=32)
    value: dict[str, Any] = fields.JSONField()

    if TYPE_CHECKING:
        user_id: UUID

    def __str__(self) -> str:
        return f"[Preference: user {self.user_id}, key {self.key}]"

    class Meta:
        table: ClassVar[str] = "preference"
        table_description: ClassVar[str] = "Preference"
        schema: ClassVar[str] = "config"
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("user", "key"),)
