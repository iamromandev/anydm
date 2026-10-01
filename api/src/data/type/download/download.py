"""Download domain enums.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from typing import Literal

from tortoise.fields.base import StrEnum


class Platform(StrEnum):
    #: Any page yt-dlp extracts, YouTube included; ``SiteDetail.extractor`` says which.
    SITE = "site"
    DIRECT = "direct"
    TORRENT = "torrent"


class Preset(StrEnum):
    BEST = "best"
    P2160 = "2160"
    P1440 = "1440"
    P1080 = "1080"
    P720 = "720"
    P480 = "480"
    MP3 = "mp3"

    @property
    def target_height(self) -> int | None:
        """The pixel height this preset asks for, or ``None`` when it names no height."""
        if self in (Preset.BEST, Preset.MP3):
            return None
        return int(self.value)


class MediaKind(StrEnum):
    """What the bytes are. How they arrive is ``Platform``; a playlist is a ``Collection``."""

    VIDEO = "video"
    AUDIO = "audio"
    FILE = "file"


class DownloadStatus(StrEnum):
    PENDING = "pending"
    DOWNLOADING = "downloading"
    MUXING = "muxing"
    PAUSED = "paused"
    #: Every selected byte has landed and the torrent is still sharing. Not
    #: terminal: the user can stop seeding, which is what moves it to COMPLETE.
    SEEDING = "seeding"
    COMPLETE = "complete"
    FAILED = "failed"
    CANCELED = "canceled"

    @property
    def is_terminal(self) -> bool:
        return self in (DownloadStatus.COMPLETE, DownloadStatus.FAILED, DownloadStatus.CANCELED)


class SegmentPart(StrEnum):
    """Which stream a segment belongs to. The values are the worker's own part names."""

    FILE = "file"
    VIDEO = "video"
    AUDIO = "audio"


class ChecksumAlgo(StrEnum):
    SHA256 = "sha256"
    SHA1 = "sha1"
    MD5 = "md5"


class CollectionKind(StrEnum):
    PLAYLIST = "playlist"
    #: A channel's own uploads: its files aren't numbered.
    CHANNEL = "channel"


#: The queue every download starts in, and the category nothing else claims.
MAIN_QUEUE = "Main"
OTHER_CATEGORY = "Other"

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
    "total_bytes",
    "-total_bytes",
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
    "downloading": frozenset({DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.MUXING}),
    "seeding": frozenset({DownloadStatus.SEEDING}),
    "completed": frozenset({DownloadStatus.COMPLETE}),
}
