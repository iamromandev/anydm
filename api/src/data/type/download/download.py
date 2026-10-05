"""Download domain enums.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from typing import Literal

from tortoise.fields.base import StrEnum


class Platform(StrEnum):
    #: Any page yt-dlp extracts, YouTube included; ``Download.provider`` says which.
    SITE = "site"
    DIRECT = "direct"
    TORRENT = "torrent"


class UrlKind(StrEnum):
    """The protocol an url speaks: what kind of address it is."""

    HTTP = "http"
    HTTPS = "https"
    FTP = "ftp"
    FTPS = "ftps"
    SFTP = "sftp"
    FILE = "file"
    MAGNET = "magnet"


class ProviderStatus(StrEnum):
    """Whether a provider answers."""

    ACTIVE = "active"
    INACTIVE = "inactive"


class SourceKind(StrEnum):
    """One way a Source uses its URL: the file itself, the page around it, or a torrent."""

    DIRECT = "direct"
    CONTENT = "content"
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
    """What a download is. How its bytes arrive is ``Platform``.

    A playlist or a channel's tab is a download too, one with children: it has no
    bytes of its own, and its state is computed from its children.
    """

    VIDEO = "video"
    AUDIO = "audio"
    FILE = "file"
    PLAYLIST = "playlist"
    #: A channel's own uploads: its videos aren't numbered.
    CHANNEL = "channel"

    @property
    def is_container(self) -> bool:
        return self in (MediaKind.PLAYLIST, MediaKind.CHANNEL)


#: The kinds that hold other downloads: never claimed, never run, their state computed from their children.
CONTAINER_KINDS = (MediaKind.PLAYLIST, MediaKind.CHANNEL)


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


class TrackerStatus(StrEnum):
    ACTIVE = "active"
    INACTIVE = "inactive"
    FAILED = "failed"


class PeerStatus(StrEnum):
    CONNECTING = "connecting"
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"


class PieceStatus(StrEnum):
    PENDING = "pending"
    DOWNLOADING = "downloading"
    COMPLETED = "completed"
    FAILED = "failed"


class SegmentStatus(StrEnum):
    PENDING = "pending"
    DOWNLOADING = "downloading"
    COMPLETED = "completed"
    FAILED = "failed"


class AttemptStatus(StrEnum):
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class MirrorStatus(StrEnum):
    AVAILABLE = "available"
    ACTIVE = "active"
    FAILED = "failed"
    EXHAUSTED = "exhausted"
    DISABLED = "disabled"


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
    CHANNEL = "channel"


#: The queue every download starts in, and the folder nothing else claims.
MAIN_QUEUE = "Main"
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
