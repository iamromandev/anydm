"""Download domain enums.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``, exactly as auth's ``Env`` is.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class Platform(StrEnum):
    YOUTUBE = "youtube"
    DIRECT = "direct"


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


class TaskStatus(StrEnum):
    PENDING = "pending"
    DOWNLOADING = "downloading"
    MUXING = "muxing"
    PAUSED = "paused"
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
