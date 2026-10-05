"""A ``transfer.Download``'s state, how it arrives, where it lands, and how the list reads it.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from typing import Literal

from tortoise.fields.base import StrEnum


class Platform(StrEnum):
    #: Any page yt-dlp extracts, YouTube included; its source's provider says which.
    SITE = "site"
    DIRECT = "direct"
    TORRENT = "torrent"


class Folder(StrEnum):
    DOWNLOADS = "downloads"
    VIDEOS = "videos"
    MOVIES = "movies"
    TV_SHOWS = "tv_shows"
    MUSIC = "music"
    AUDIOBOOKS = "audiobooks"
    PODCASTS = "podcasts"
    DOCUMENTS = "documents"
    EBOOKS = "ebooks"
    IMAGES = "images"
    PHOTOS = "photos"
    SOFTWARE = "software"
    GAMES = "games"
    ARCHIVES = "archives"
    OTHER = "other"


class DownloadStatus(StrEnum):
    PENDING = "pending"
    QUEUED = "queued"
    DOWNLOADING = "downloading"
    #: Every byte is down and the parts are being joined into the final file.
    MUXING = "muxing"
    PAUSED = "paused"
    #: Every selected byte has landed and the torrent is still sharing. Not
    #: terminal: the user can stop seeding, which is what moves it to COMPLETED.
    SEEDING = "seeding"
    COMPLETED = "completed"
    FAILED = "failed"
    #: Removed by the person. The row also gets ``deleted_at`` and drops out of every list.
    CANCELLED = "cancelled"


class CollectionKind(StrEnum):
    PLAYLIST = "playlist"
    CHANNEL = "channel"


#: The folder nothing else claims.
OTHER_FOLDER = "Other"


#: Statuses that mean "a worker was mid-flight". Every row in one of these at
#: startup is an orphan by definition — this process is the only one that runs
#: workers, and it has just started.
ACTIVE_STATUSES = frozenset({DownloadStatus.DOWNLOADING, DownloadStatus.MUXING})


#: How the list may be ordered. Spelled out both ways rather than as a field
#: plus a direction, so an unknown value is a 422 from the route rather than
#: something the service has to think about.
DownloadSort = Literal[
    "created_at",
    "-created_at",
    "title",
    "-title",
    "total_size",
    "-total_size",
    "progress",
    "-progress",
    "speed_bps",
    "-speed_bps",
]


#: The bulk actions the API accepts. Which rows each one applies to is the
#: service's business, in ``BULK_SCOPES``; a test keeps the two in step.
BulkAction = Literal["pause_all", "resume_all", "clear_finished"]


#: The filter names the API accepts, which are the sidebar's own.
DownloadGroup = Literal["all", "downloading", "seeding", "completed"]


#: What each of the sidebar's filters means. Deliberately not ``ACTIVE_STATUSES``:
#: that answers "was a worker mid-flight", which excludes ``PENDING`` because a
#: queued row is not an orphan. To someone reading the list, a queued row is active.
DOWNLOAD_GROUPS: dict[str, frozenset[DownloadStatus]] = {
    "downloading": frozenset(
        {DownloadStatus.PENDING, DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.MUXING}
    ),
    "seeding": frozenset({DownloadStatus.SEEDING}),
    "completed": frozenset({DownloadStatus.COMPLETED}),
}
