"""What ffmpeg and ffprobe read: a URL, and the headers its server expects."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class MediaInput:
    url: str
    #: Sent with every request for ``url``. A site's media server often checks
    #: them: YouTube's refuses a request without the User-Agent it resolved for.
    headers: dict[str, str] = field(default_factory=dict)


def headers_args(headers: Mapping[str, str] | None) -> list[str]:
    """``-headers`` for the input that follows, or nothing when there are none.

    An input option: it has to come before the ``-i`` (or the URL) it is for.
    ffmpeg wants one block of CRLF-terminated lines, and sends its own
    User-Agent only when the block has none.
    """
    if not headers:
        return []
    return ["-headers", "".join(f"{name}: {value}\r\n" for name, value in headers.items())]
