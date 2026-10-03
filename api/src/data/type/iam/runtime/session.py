"""Session enums.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class SessionKind(StrEnum):
    #: A browser login.
    SESSION = "session"
    #: A token a person created, with a name, for a script or a client.
    API = "api"
