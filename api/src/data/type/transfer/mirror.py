"""A ``transfer.Mirror``'s standing.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class MirrorStatus(StrEnum):
    AVAILABLE = "available"
    ACTIVE = "active"
    FAILED = "failed"
    EXHAUSTED = "exhausted"
    DISABLED = "disabled"
