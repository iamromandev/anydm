from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase


class Mirror(LinkBase):
    """Another address for a direct download, tried in ``position`` order when one fails."""

    download = fields.ForeignKeyField("model.Download", related_name="mirrors", on_delete=fields.CASCADE)
    url: str = fields.TextField()
    position: int = fields.IntField()
    #: Why this address failed the last time it was tried.
    last_error: str | None = fields.TextField(null=True)

    if TYPE_CHECKING:
        download_id: UUID

    class Meta:
        table: ClassVar[str] = "mirror"
        table_description: ClassVar[str] = "Mirror"
        ordering: ClassVar[list[str]] = ["position"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("download", "position"),)
