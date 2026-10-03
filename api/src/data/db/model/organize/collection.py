from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import Base
from src.data.type import CollectionKind, Preset


class Collection(Base):
    """A playlist or a channel's tab, added as one group. Its status and totals are computed.

    Unique per ``(extractor, ref_id)`` while not deleted: a partial index
    written by hand in ``0001_initial``, which ``CreateModel`` would not apply.
    """
    folder = fields.ForeignKeyField(
        to="model.Folder",
        related_name="collections",
        null=True,
        on_delete=fields.RESTRICT
    )
    title: str = fields.CharField(max_length=512, default="")
    ref_id: str = fields.CharField(max_length=128)
    kind: CollectionKind = fields.CharEnumField(CollectionKind, max_length=16)
    preset: Preset = fields.CharEnumField(enum_type=Preset, default=Preset.BEST)
    extractor: str = fields.CharField(max_length=64)

    def __str__(self) -> str:
        return f"[Collection: {self.kind} {self.extractor}:{self.ref_id}]"

    class Meta:
        table: ClassVar[str] = "collection"
        table_description: ClassVar[str] = "Collection"
        schema: ClassVar[str] = "organize"
        ordering: ClassVar[list[str]] = ["-created_at"]
