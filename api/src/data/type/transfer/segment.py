"""A ``transfer.Segment``'s stream and progress.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class SegmentStatus(StrEnum):
    PENDING = "pending"
    DOWNLOADING = "downloading"
    COMPLETED = "completed"
    FAILED = "failed"


class SegmentPart(StrEnum):
    """Which stream a segment belongs to. The values are the worker's own part names."""

    FILE = "file"
    VIDEO = "video"
    AUDIO = "audio"
