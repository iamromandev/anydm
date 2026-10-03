"""User enums.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class UserRole(StrEnum):
    #: Manages queues, global limits and other users.
    ADMIN = "admin"
    USER = "user"
