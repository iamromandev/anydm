"""What a ``transfer.Media`` is, and the quality it asks for.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


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
