from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import SourceKind


class Source(LinkBase):
    """One URL used one way: the file itself, the page around it, or a torrent."""

    provider = fields.ForeignKeyField(
        to="model.Provider",
        related_name="sources",
        # A provider that downloads point at is not deleted out from under them.
        on_delete=fields.RESTRICT
    )
    url = fields.ForeignKeyField(
        to="model.Url",
        related_name="sources",
        on_delete=fields.RESTRICT
    )

    kind: SourceKind = fields.CharEnumField(
        enum_type=SourceKind, default=SourceKind.DIRECT
    )
    #: The torrent a torrent source leads to, none for any other kind; several sources can lead to one.
    torrent = fields.ForeignKeyField(
        to="model.Torrent", related_name="sources", null=True, on_delete=fields.SET_NULL
    )

    if TYPE_CHECKING:
        provider_id: UUID
        url_id: UUID
        torrent_id: UUID | None

    def __str__(self) -> str:
        return f"[Source: {self.kind} {self.url.id}]"

    class Meta:
        table: ClassVar[str] = "source"
        table_description: ClassVar[str] = "Source"
        schema: ClassVar[str] = "catalog"
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("provider", "url", "kind"),)
