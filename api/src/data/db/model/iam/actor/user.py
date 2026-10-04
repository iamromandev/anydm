from __future__ import annotations

from datetime import datetime
from typing import ClassVar

from tortoise import fields

from src.core.base import Base
from src.data.type import UserRole


class User(Base):
    """A person who signs in. Deactivating one blocks login and keeps their data.

    ``folder`` is the id as text, fixed at creation: the user's root under
    ``DOWNLOAD_DIR``. It is never the username, which a person can change, so a
    finished download's files stay where its row says they are.
    """

    username: str = fields.CharField(max_length=64, unique=True)
    display_name: str = fields.CharField(max_length=128, default="")
    role: UserRole = fields.CharEnumField(
        enum_type=UserRole,
        default=UserRole.USER
    )
    is_active: bool = fields.BooleanField(default=True)
    password_hash: str = fields.CharField(max_length=255)
    locale: str | None = fields.CharField(max_length=35, null=True)
    timezone: str | None = fields.CharField(max_length=64, null=True)
    last_login_at: datetime | None = fields.DatetimeField(null=True)

    def __str__(self) -> str:
        return f"[User: {self.username}, role {self.role}]"

    class Meta:
        table: ClassVar[str] = "user"
        table_description: ClassVar[str] = "User"
        schema: ClassVar[str] = "iam"
