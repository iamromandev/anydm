"""A piece's progress, as ``torrent.Piece`` once kept it. Nothing stores it now (a download's ``pieces`` bitmap does);
kept because migration 0006 imports it.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class PieceStatus(StrEnum):
    PENDING = "pending"
    DOWNLOADING = "downloading"
    COMPLETED = "completed"
    FAILED = "failed"
