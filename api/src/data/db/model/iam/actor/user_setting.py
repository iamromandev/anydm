from __future__ import annotations

from typing import TYPE_CHECKING, Any, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase


class UserSetting(LinkBase):
    """One preference of one user, the ones the settings modal stores."""

    user = fields.ForeignKeyField("model.User", related_name="user_settings", on_delete=fields.CASCADE)
    key: str = fields.CharField(max_length=64)
    value: Any = fields.JSONField()

    if TYPE_CHECKING:
        user_id: UUID

    def __str__(self) -> str:
        return f"[UserSetting: user {self.user_id}, key {self.key}]"

    class Meta:
        table: ClassVar[str] = "user_setting"
        table_description: ClassVar[str] = "UserSetting"
        schema: ClassVar[str] = "iam"
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("user", "key"),)
