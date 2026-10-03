"""Share enums.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``. What a share points at is ``RefType``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class ShareRole(StrEnum):
    VIEW = "view"
    #: Pause, resume, delete and edit.
    MANAGE = "manage"
