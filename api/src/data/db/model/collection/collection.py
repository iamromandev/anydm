from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import Base
from src.data.type import CollectionKind, Preset


class Collection(Base):
    """A playlist or a channel's tab, added as one group. Its status and totals are computed.

    Unique per ``(extractor, external_id)`` while not deleted: a partial index
    written by hand in ``0001_initial``, which ``CreateModel`` would not apply.
    """

    kind: CollectionKind = fields.CharEnumField(CollectionKind, max_length=16)
    source_url: str = fields.TextField()
    #: The listing's extractor: "YoutubeTab", not its videos' "Youtube".
    extractor: str = fields.CharField(max_length=64)
    external_id: str = fields.CharField(max_length=128)
    title: str = fields.CharField(max_length=512, default="")
    #: Relative to ``DOWNLOAD_DIR``; its videos finish into it.
    folder: str = fields.CharField(max_length=1024)
    #: A ceiling for each video.
    preset: Preset = fields.CharEnumField(Preset, max_length=8)

    def __str__(self) -> str:
        return f"[Collection: {self.kind} {self.extractor}:{self.external_id}]"

    class Meta:
        table: ClassVar[str] = "collection"
        table_description: ClassVar[str] = "Collection"
        ordering: ClassVar[list[str]] = ["-created_at"]
