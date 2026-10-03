"""Share enums.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class ShareResource(StrEnum):
    """What a ``Share`` can point at. Sharing a collection shares its downloads."""

    DOWNLOAD = "download"
    COLLECTION = "collection"


class ShareRole(StrEnum):
    VIEW = "view"
    #: Pause, resume, delete and edit.
    MANAGE = "manage"
