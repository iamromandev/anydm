"""The kinds of row a loose reference can name.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class RefType(StrEnum):
    """Which table a ``ref_id`` belongs to. A new taggable model adds a value here and nothing else."""

    DOWNLOAD = "download"
    COLLECTION = "collection"
