from typing import Any

from tortoise import fields
from tortoise.fields.base import StrEnum

from src.core.base import Base
from src.db.validators import UrlValidator


class Platform(Base):
    class Type(StrEnum):
        STREAMING = "streaming"
        SOCIAL_MEDIA = "social_media"
        STORAGE = "storage"
        CLOUD = "cloud"
        BROADCASTING = "broadcasting"
        GALLERY = "gallery"
        MUSIC = "music"
        EDUCATIONAL = "educational"
        GAMING = "gaming"
        NEWS = "news"

    type: Type = fields.CharEnumField(Type, default=Type.STREAMING, db_index=True)
    name: str = fields.CharField(max_length=128, unique=True, db_index=True)
    slug: str = fields.CharField(max_length=255, unique=True, db_index=True)
    description: str | None = fields.TextField(null=True)
    website: str | None = fields.CharField(
        max_length=128,
        null=True,
        unique=True,
        db_index=True,
        validators=[UrlValidator()]
    )
    icon: str | None = fields.TextField(
        null=True,
        validators=[UrlValidator()]
    )
    meta: dict[str, Any] | list[None] | None = fields.JSONField(null=True, default=None)

    class Meta:
        ordering = ["name"]
        table = "platform"
        table_description = "Platform"
