"""Plain HTTP downloads — anything that is not a media platform."""

from __future__ import annotations

import re
from pathlib import PurePosixPath
from urllib.parse import unquote, urlparse

from src.core.error import Error
from src.core.type import Code, ErrorType

_UNSAFE = re.compile(r"[^\w.\-]", re.UNICODE)
_ALLOWED_SCHEMES = frozenset({"http", "https"})


def ensure_fetchable(url: str) -> None:
    """Reject anything the downloader has no business fetching.

    ``file://`` in particular: this endpoint takes a URL from a browser, and
    handing that straight to a client that would read the local filesystem is
    how a download manager becomes a file-disclosure endpoint.
    """
    if urlparse(url).scheme not in _ALLOWED_SCHEMES:
        raise Error.create(
            code=Code.BAD_REQUEST,
            message="Only http and https URLs can be downloaded",
            error_type=ErrorType.UNSUPPORTED_OPERATION,
        )


def filename_from_url(url: str) -> str:
    """A safe filename from the URL's last path segment.

    ``PurePosixPath(...).name`` drops every directory component, so a path
    walking upward cannot escape the task directory.
    """
    path = unquote(urlparse(url).path)
    # A trailing slash names a directory, and PurePosixPath would normalise it
    # away and hand back the directory's own name as if it were a file.
    stem = "" if path.endswith("/") else PurePosixPath(path).name
    cleaned = _UNSAFE.sub("_", stem).strip("._")
    return cleaned or "download"
