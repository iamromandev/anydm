from typing import Any

from tortoise import fields
from tortoise.fields.base import StrEnum

from src.core.base import Base


class File(Base):
    class Source(StrEnum):
        LOCAL = "local"  # Local filesystem
        URL = "url"  # External URL
        VIRTUAL = "virtual"  # Generated content
        CLOUD = "cloud"  # Cloud storage (S3, etc.)

    source = fields.CharEnumField(Source, default=Source.LOCAL, db_index=True)
    mime_type: str = fields.CharField(max_length=128, db_index=True)
    name: str = fields.CharField(max_length=255, db_index=True)
    path: str = fields.TextField()
    size: int = fields.BigIntField(null=True)
    meta: dict[str, Any] | list[None] | None = fields.JSONField(null=True, default=None)

    class Meta:
        ordering = ["name"]
        table = "file"
        table_description = "File"
