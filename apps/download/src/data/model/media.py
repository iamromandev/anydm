from typing import Any

from tortoise import fields

from src.core.base import Base

from .file import File
from .platform import Platform


class Media(Base):
    platform: fields.ForeignKeyRelation["Platform"] = fields.ForeignKeyField(
        model_name="model.Platform",
        related_name="medias",
    )
    file: fields.OneToOneRelation["File"] = fields.OneToOneField(
        model_name="model.File",
        related_name="media"
    )
    name: str = fields.CharField(max_length=128, db_index=True)
    slug: str = fields.CharField(max_length=255, db_index=True)
    description: str | None = fields.TextField(null=True)
    meta: dict[str, Any] | list[None] | None = fields.JSONField(null=True, default=None)

    class Meta:
        ordering = ["name"]
        table = "media"
        table_description = "Media"
