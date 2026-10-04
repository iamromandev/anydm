from __future__ import annotations

import uuid
from datetime import datetime
from typing import Annotated, Literal

from pydantic import Field

from src.core.base import BaseSchema
from src.data.type import BulkAction, CollectionKind, DownloadStatus, MediaKind, Platform, Preset


class PlaybackSchema(BaseSchema):
    """Where one file was left in the player (#96)."""

    position_seconds: float = 0.0
    duration_seconds: float = 0.0
    #: Played to within its last seconds, at least once.
    watched: bool = False


class PlaybackRequest(BaseSchema):
    position_seconds: Annotated[float, Field(ge=0)]
    duration_seconds: Annotated[float, Field(ge=0)]


class FolderRef(BaseSchema):
    id: uuid.UUID
    name: str


class QueueRef(BaseSchema):
    id: uuid.UUID
    name: str


class LimitsSchema(BaseSchema):
    #: Null: the global limit alone.
    download_bps: int | None = None


class LiveSchema(BaseSchema):
    """``LiveStats``: never stored, zero after a restart until the first tick."""

    speed_bps: int = 0
    eta_seconds: int | None = None
    upload_speed_bps: int = 0
    peers: int = 0


class SiteSchema(BaseSchema):
    #: yt-dlp's name for the site: "Youtube", "Vimeo", ...
    extractor: str
    video_id: str
    preset: Preset
    video_format: str | None = None
    audio_format: str | None = None


class TorrentInfoSchema(BaseSchema):
    info_hash: str
    uploaded_bytes: int = 0


class DownloadFileSchema(BaseSchema):
    index: int
    path: str
    size_bytes: int = 0
    downloaded_bytes: int = 0
    selected: bool = True
    mime_type: str | None = None
    playback: PlaybackSchema | None = None


class DownloadSchema(BaseSchema):
    type: Literal["download"] = "download"
    id: uuid.UUID
    source_url: str
    platform: Platform
    media_kind: MediaKind
    title: str = ""
    status: DownloadStatus
    progress: int = 0
    #: The key stays ``category``: the clients read it, and the contract is not part of this change.
    category: FolderRef | None = None
    #: The collection it was added in; ``None`` for a standalone download.
    collection_id: uuid.UUID | None = None
    queue: QueueRef | None = None
    queue_position: int = 0
    start_at: datetime | None = None
    folder: str | None = None
    limits: LimitsSchema = Field(default_factory=LimitsSchema)
    total_bytes: int | None = None
    downloaded_bytes: int = 0
    live: LiveSchema = Field(default_factory=LiveSchema)
    site: SiteSchema | None = None
    torrent: TorrentInfoSchema | None = None
    files: list[DownloadFileSchema] = Field(default_factory=list)
    error: str | None = None
    error_code: str | None = None
    attempts: int = 0
    #: The retry budget, from settings: identical for every download, so no column.
    max_attempts: int = 0
    #: When the queue will consider this download again, while a retry is pending.
    next_attempt_at: datetime | None = None
    created_at: datetime | None = None
    started_at: datetime | None = None
    completed_at: datetime | None = None


class CollectionCountsSchema(BaseSchema):
    """How a collection's videos stand, by kind of status."""

    total: int = 0
    complete: int = 0
    #: Queued or downloading.
    active: int = 0
    #: Downloading or muxing, of ``active``.
    downloading: int = 0
    paused: int = 0
    failed: int = 0
    #: Watched to the end; counted when the list is read, absent from frames.
    watched: int | None = None


class CollectionSchema(BaseSchema):
    type: Literal["collection"] = "collection"
    id: uuid.UUID
    kind: CollectionKind
    extractor: str
    external_id: str
    title: str = ""
    folder: str
    preset: Preset
    status: DownloadStatus
    progress: int = 0
    counts: CollectionCountsSchema = Field(default_factory=CollectionCountsSchema)
    total_bytes: int | None = None
    downloaded_bytes: int = 0
    #: The sum of its members' live speeds.
    speed_bps: int = 0
    created_at: datetime | None = None


ListItem = DownloadSchema | CollectionSchema


class MediaDownloadRequest(BaseSchema):
    url: Annotated[str, Field(min_length=1, description="A page on any supported site: YouTube, Vimeo, ...")]
    preset: Annotated[Preset, Field(default=Preset.BEST, description="Quality preset")]


class UrlDownloadRequest(BaseSchema):
    url: Annotated[str, Field(min_length=1, description="A direct http or https URL")]


class CollectionEntryRequest(BaseSchema):
    """One chosen video, as ``GET /extract/entries`` listed it."""

    index: Annotated[int, Field(ge=1)]
    id: Annotated[str, Field(min_length=1)]
    url: Annotated[str, Field(pattern=r"^https?://")]
    title: str | None = None
    duration: int | None = None


class CollectionRequest(BaseSchema):
    """A playlist's or a channel tab's chosen videos, added as one collection."""

    url: Annotated[str, Field(min_length=1, description="The playlist, or the channel's tab")]
    extractor: str
    external_id: Annotated[str, Field(min_length=1)]
    title: str = ""
    #: A channel's own uploads: its files aren't numbered.
    channel_tab: bool = False
    preset: Annotated[Preset, Field(default=Preset.BEST, description="Quality preset, a ceiling for each video")]
    entries: Annotated[list[CollectionEntryRequest], Field(min_length=1)]


class BulkActionRequest(BaseSchema):
    action: Annotated[BulkAction, Field(description="Which sweep to run")]
    delete_files: Annotated[
        bool,
        Field(
            default=False,
            description=(
                "For clear_finished: take the files of finished downloads too. "
                "A failure's partial file goes either way — there is nothing "
                "in it worth keeping."
            ),
        ),
    ]


class BulkResultSchema(BaseSchema):
    affected: int = 0


class DownloadSummarySchema(BaseSchema):
    """How many list items each sidebar filter would show, counted in the database."""

    all: int = 0
    downloading: int = 0
    seeding: int = 0
    completed: int = 0
