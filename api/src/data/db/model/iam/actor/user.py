from __future__ import annotations

from datetime import datetime
from typing import TYPE_CHECKING, ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import UserRole

if TYPE_CHECKING:
    from src.data.db.model.iam.actor.user_setting import UserSetting
    from src.data.db.model.iam.membership.share import Share
    from src.data.db.model.iam.runtime.session import Session


class User(LinkBase):
    """A person who signs in. Deactivating one blocks login and keeps their data.

    ``folder`` is the id as text, fixed at creation: the user's root under
    ``DOWNLOAD_DIR``. It is never the username, which a person can change, so a
    finished download's files stay where its row says they are.
    """

    username: str = fields.CharField(max_length=64, unique=True)
    display_name: str = fields.CharField(max_length=128, default="")
    #: Never returned by the API and never logged.
    password_hash: str = fields.CharField(max_length=255)
    role: UserRole = fields.CharEnumField(UserRole, max_length=8, default=UserRole.USER)
    is_active: bool = fields.BooleanField(default=True)
    path: str = fields.CharField(max_length=64, unique=True)
    last_login_at: datetime | None = fields.DatetimeField(null=True)

    if TYPE_CHECKING:
        sessions: fields.ReverseRelation[Session]
        shares: fields.ReverseRelation[Share]
        granted_shares: fields.ReverseRelation[Share]
        user_settings: fields.ReverseRelation[UserSetting]

    def __str__(self) -> str:
        return f"[User: {self.username}, role {self.role}]"

    class Meta:
        table: ClassVar[str] = "user"
        table_description: ClassVar[str] = "User"
        schema: ClassVar[str] = "iam"
