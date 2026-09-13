from typing import Annotated

from pydantic import Field

from src.core.base import BaseSchema


class ExtractRequest(BaseSchema):
    url: Annotated[str, Field(min_length=1, description="The URL to inspect")]


class ThumbnailSchema(BaseSchema):
    url: str
    width: int = 0
    height: int = 0


class FormatSchema(BaseSchema):
    itag: int
    quality: Annotated[str, Field(default="unknown")]
    container: Annotated[str, Field(default="unknown")]
    has_video: bool = False
    has_audio: bool = False
    content_length: int | None = None
    mime_type: str | None = None


class ExtractSchema(BaseSchema):
    platform: Annotated[str, Field(default="youtube")]
    video_id: str
    title: str = ""
    author: str = ""
    channel_id: str = ""
    description: str = ""
    length_seconds: int = 0
    view_count: int = 0
    upload_date: str = ""
    is_live: bool = False
    thumbnail: str = ""
    thumbnails: Annotated[list[ThumbnailSchema], Field(default_factory=list)]
    formats: Annotated[list[FormatSchema], Field(default_factory=list)]
