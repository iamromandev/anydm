"""Download domain enums.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``, exactly as auth's ``Env`` is.
"""

from __future__ import annotations

from typing import Literal

from tortoise.fields.base import StrEnum


class Platform(StrEnum):
    YOUTUBE = "youtube"
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


class Kind(StrEnum):
    VIDEO = "video"
    AUDIO = "audio"
    #: An arbitrary fetched file — what ``Platform.DIRECT`` produces. It has no
    #: notion of stream quality, so no preset applies to it.
    FILE = "file"
    #: A whole torrent, which may hold many files. The selected ones live in
    #: ``torrent_file``; this row is the torrent itself.
    TORRENT = "torrent"


class TaskStatus(StrEnum):
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
        return self in (TaskStatus.COMPLETE, TaskStatus.FAILED, TaskStatus.CANCELED)


#: Statuses that mean "a worker was mid-flight". Every row in one of these at
#: startup is an orphan by definition — this process is the only one that runs
#: workers, and it has just started.
ACTIVE_STATUSES = frozenset({TaskStatus.DOWNLOADING, TaskStatus.MUXING})

#: What each of the sidebar's filters means, keyed by the name the UI already
#: uses for it. Deliberately not ``ACTIVE_STATUSES``: that answers "was a
#: worker mid-flight", which excludes ``PENDING`` because a queued row is not
#: an orphan. To someone reading the list, a queued row is very much active.
#: How the list may be ordered. Spelled out both ways rather than as a field
#: plus a direction, so an unknown value is a 422 from the route rather than
#: something this service has to think about.
TaskSort = Literal[
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
TaskGroup = Literal["all", "downloading", "seeding", "completed"]

TASK_GROUPS: dict[str, frozenset[TaskStatus]] = {
    "downloading": frozenset(
        {TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.MUXING}
    ),
    "seeding": frozenset({TaskStatus.SEEDING}),
    "completed": frozenset({TaskStatus.COMPLETE}),
}
