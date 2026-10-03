from __future__ import annotations

from datetime import datetime
from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import SessionKind


class Session(LinkBase):
    """A browser login or an API token. Only the token's hash is stored."""

    user = fields.ForeignKeyField("model.User", related_name="sessions", on_delete=fields.CASCADE)
    kind: SessionKind = fields.CharEnumField(SessionKind, max_length=8)
    #: What the person called an API token; null for a browser login.
    name: str | None = fields.CharField(max_length=64, null=True)
    token_hash: str = fields.CharField(max_length=64, unique=True)
    #: Null: an API token that does not expire.
    expires_at: datetime | None = fields.DatetimeField(null=True)
    last_used_at: datetime | None = fields.DatetimeField(null=True)
    user_agent: str = fields.CharField(max_length=255, default="")

    if TYPE_CHECKING:
        user_id: UUID

    def __str__(self) -> str:
        return f"[Session: user {self.user_id}, kind {self.kind}]"

    class Meta:
        table: ClassVar[str] = "session"
        table_description: ClassVar[str] = "Session"
        schema: ClassVar[str] = "iam"
