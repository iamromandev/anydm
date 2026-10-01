from datetime import datetime
from typing import Annotated

from pydantic import Field, model_validator

from src.core.base import BaseSchema


class SearchResultSchema(BaseSchema):
    title: str
    size: int | None = None
    seeders: int | None = None
    leechers: int | None = None
    published: datetime | None = None
    category: str = "other"
    info_hash: str | None = None
    #: Exactly one of ``magnet`` and ``link``: the copy the client is to use.
    magnet: str | None = None
    link: str | None = None
    #: The source that copy came from; ``indexers`` names every source that had the result.
    copy_from: str
    indexers: list[str]

    @model_validator(mode="after")
    def _one_copy(self) -> SearchResultSchema:
        """One copy, so a client never has to choose between a magnet and a link."""
        if (self.magnet is None) == (self.link is None):
            raise ValueError("Give exactly one of magnet and link")
        return self


class IndexerErrorSchema(BaseSchema):
    indexer: str
    message: str


class SearchSchema(BaseSchema):
    results: list[SearchResultSchema]
    errors: list[IndexerErrorSchema]
    #: Every source the request was sent to, whether it answered or failed.
    asked: list[str]
    took_ms: int


class SourcesSchema(BaseSchema):
    enabled: bool
    indexers: list[str]
    #: Always on: the site client is always there.
    youtube: bool = True


class SearchTorrentRequest(BaseSchema):
    link: Annotated[str, Field(min_length=1, max_length=4096, description="A result's link, from GET /search")]


class SearchTorrentSchema(BaseSchema):
    """One of the two: the file base64-encoded, or the magnet the indexer redirected to."""

    torrent: str | None = None
    magnet: str | None = None


class BuiltinSourceSchema(BaseSchema):
    name: str
    label: str
    enabled: bool
    base_url: str
    default_url: str


class BuiltinSourcesSchema(BaseSchema):
    sources: list[BuiltinSourceSchema]


class BuiltinSourcePatch(BaseSchema):
    enabled: bool | None = None
    base_url: str | None = None

    @model_validator(mode="after")
    def _something_to_change(self) -> BuiltinSourcePatch:
        if self.enabled is None and self.base_url is None:
            raise ValueError("Give enabled, base_url, or both")
        return self


class BuiltinTestRequest(BaseSchema):
    base_url: str | None = None


class BuiltinTestSchema(BaseSchema):
    ok: bool
    #: How many results it found; absent when the test failed.
    count: int | None = None
    took_ms: int
    message: str


class VideoSchema(BaseSchema):
    title: str
    #: The watch page; what Add sends to the download flow.
    url: str
    channel: str | None = None
    duration: int | None = None
    thumbnail: str | None = None
    views: int | None = None
    published: str | None = None


class SearchVideosSchema(BaseSchema):
    results: list[VideoSchema]
    took_ms: int
