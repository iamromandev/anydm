from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields
from tortoise.indexes import Index

from src.core.base import LinkBase
from src.data.type import RefType, ShareRole


class Share(LinkBase):
    """Lets one user, or everyone signed in, see or manage a download or collection.

    ``ref_id`` has no foreign key: it points at one of several tables, and only
    downloads and collections are shareable. The repository checks the target,
    and the visibility helper reads the reference.

    A null ``user`` means everyone signed in. ``unique_together`` cannot stop two
    of those for one row (a NULL is never equal to another in Postgres), so a
    partial unique index written by hand in the migration does.
    """

    ref_type: RefType = fields.CharEnumField(RefType, max_length=16)
    ref_id: UUID = fields.UUIDField()
    user = fields.ForeignKeyField("model.User", related_name="shares", null=True, on_delete=fields.CASCADE)
    role: ShareRole = fields.CharEnumField(ShareRole, max_length=8)
    #: The owner or an admin. RESTRICT: a user who granted shares is not deleted by accident.
    created_by = fields.ForeignKeyField("model.User", related_name="granted_shares", on_delete=fields.RESTRICT)

    if TYPE_CHECKING:
        user_id: UUID | None
        created_by_id: UUID

    def __str__(self) -> str:
        return f"[Share: {self.ref_type} {self.ref_id}, user {self.user_id}, role {self.role}]"

    class Meta:
        table: ClassVar[str] = "share"
        table_description: ClassVar[str] = "Share"
        schema: ClassVar[str] = "iam"
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("ref_type", "ref_id", "user"),)
        indexes: ClassVar[tuple[Index, ...]] = (
            Index(fields=["user_id", "ref_type"], name="idx_share_user_ref_type"),
            Index(fields=["ref_type", "ref_id"], name="idx_share_ref"),
        )
