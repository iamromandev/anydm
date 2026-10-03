from __future__ import annotations

import uuid
from typing import ClassVar

from tortoise import fields
from tortoise.indexes import Index

from src.core.base import LinkBase
from src.data.type import RefType


class Tag(LinkBase):
    """A label on one row of any table, named by that row's kind and id.

    Each row carries its own tags, so "Music" on two downloads is two rows. What
    they share is the ``slug``: "everything tagged music" is a lookup by ``slug``
    and ``ref_type``.

    ``ref_id`` has no foreign key: it points at one of several tables, so the
    database cannot check that the row exists or remove its tags with it. The
    repository checks the target when it adds a tag and removes the tags when it
    removes a row. A new taggable model needs one new ``RefType`` value.
    """

    name: str = fields.CharField(max_length=64)
    slug: str = fields.CharField(max_length=64)
    ref_type: RefType = fields.CharEnumField(enum_type=RefType, default=RefType.DOWNLOAD)
    ref_id: uuid.UUID = fields.UUIDField(db_index=True)

    def __str__(self) -> str:
        return f"[Tag: {self.slug} on {self.ref_type} {self.ref_id}]"

    class Meta:
        table: ClassVar[str] = "tag"
        table_description: ClassVar[str] = "Tag"
        schema: ClassVar[str] = "shared"
        ordering: ClassVar[list[str]] = ["name"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("ref_type", "ref_id", "slug"),)
        indexes: ClassVar[tuple[Index, ...]] = (Index(fields=["ref_type", "slug"], name="idx_tag_ref_type_slug"),)
