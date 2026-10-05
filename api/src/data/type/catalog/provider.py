"""Whether a ``catalog.Provider`` answers.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class ProviderStatus(StrEnum):
    """Whether a provider answers."""

    ACTIVE = "active"
    INACTIVE = "inactive"
