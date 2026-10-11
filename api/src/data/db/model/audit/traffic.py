from __future__ import annotations

from datetime import date
from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import SourceKind


class Traffic(LinkBase):
    """What moved on one UTC day through one kind of source and one provider (#362).

    A record of what happened, so it outlives the downloads it counts: removing a
    download, or a provider, leaves the totals. ``provider`` is blanked when its
    row goes; the bytes and counts stay.

    Direct files and torrents each have a single provider (``http``, ``torrent``),
    so only a site's rows tell sites apart.
    """

    day: date = fields.DateField(db_index=True)
    kind: SourceKind = fields.CharEnumField(enum_type=SourceKind, max_length=7)
    provider = fields.ForeignKeyField("model.Provider", related_name="traffics", null=True, on_delete=fields.SET_NULL)
    downloaded_size: int = fields.BigIntField(default=0)
    uploaded_size: int = fields.BigIntField(default=0)
    finished: int = fields.IntField(default=0)
    failed: int = fields.IntField(default=0)
    removed: int = fields.IntField(default=0)

    if TYPE_CHECKING:
        provider_id: UUID | None

    def __str__(self) -> str:
        return f"[Traffic: {self.day} {self.kind}, {self.downloaded_size} down]"

    class Meta:
        table: ClassVar[str] = "traffic"
        table_description: ClassVar[str] = "Traffic"
        schema: ClassVar[str] = "audit"
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("day", "kind", "provider"),)
