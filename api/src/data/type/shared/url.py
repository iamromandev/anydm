"""The protocols an address can speak, for ``shared.Url``.

These subclass Tortoise's ``StrEnum`` rather than the standard library's so they
can be used directly in ``CharEnumField``.
"""

from __future__ import annotations

from tortoise.fields.base import StrEnum


class UrlKind(StrEnum):
    """The protocol an url speaks: what kind of address it is."""

    HTTP = "http"
    HTTPS = "https"
    FTP = "ftp"
    FTPS = "ftps"
    SFTP = "sftp"
    FILE = "file"
    MAGNET = "magnet"
