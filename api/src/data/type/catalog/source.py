"""How a ``catalog.Source`` uses its address.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class SourceKind(StrEnum):
    """One way a Source uses its URL: the file itself, the page around it, or a torrent."""

    DIRECT = "direct"
    CONTENT = "content"
    TORRENT = "torrent"
