from datetime import datetime
from typing import Annotated

from pydantic import Field

from src.core.base import BaseSchema


class SearchResultSchema(BaseSchema):
    title: str
    size: int | None = None
    seeders: int | None = None
    leechers: int | None = None
    published: datetime | None = None
    category: str = "other"
    info_hash: str | None = None
    magnet: str | None = None
    link: str | None = None
    indexers: list[str]


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


class SearchTorrentRequest(BaseSchema):
    link: Annotated[str, Field(min_length=1, max_length=4096, description="A result's link, from GET /search")]


class SearchTorrentSchema(BaseSchema):
    """One of the two: the file base64-encoded, or the magnet the indexer redirected to."""

    torrent: str | None = None
    magnet: str | None = None
