"""One ranged GET that answers both questions a segmented transfer needs.

A HEAD would be cheaper and is not trustworthy: plenty of servers advertise
``Accept-Ranges`` on HEAD and then ignore ``Range`` on GET, and plenty more
return a ``Content-Length`` for HEAD that is not the length of the body they
would actually serve. Asking for one byte proves the behaviour instead of the
advertisement, and costs one round trip.
"""

from __future__ import annotations

from dataclasses import dataclass

import httpx
from loguru import logger

from src.core.error import Error
from src.core.type import Code, ErrorType

_RETRYABLE_STATUSES = frozenset({403, 408, 409, 425, 429, 500, 502, 503, 504})


@dataclass(frozen=True, slots=True)
class Probe:
    total_bytes: int | None
    accepts_ranges: bool
    resolved_url: str


def _total_from_content_range(value: str | None) -> int | None:
    """``bytes 0-0/4096`` and ``bytes */4096`` both carry the whole size."""
    if not value or "/" not in value:
        return None
    tail = value.rsplit("/", 1)[1].strip()
    return int(tail) if tail.isdigit() else None


async def probe(client: httpx.AsyncClient, url: str) -> Probe:
    async with client.stream("GET", url, headers={"Range": "bytes=0-0"}, follow_redirects=True) as response:
        resolved = str(response.url)

        # 416 means the range was understood and rejected. The size in
        # ``Content-Range`` is still good, but a server this particular is not
        # one to split a download across.
        if response.status_code == 416:
            return Probe(_total_from_content_range(response.headers.get("content-range")), False, resolved)

        if response.status_code >= 400:
            retry_able = response.status_code in _RETRYABLE_STATUSES
            logger.warning("probe({}): status {}", url, response.status_code)
            raise Error.create(
                code=Code.BAD_GATEWAY,
                message=f"Upstream returned status {response.status_code}",
                error_type=ErrorType.EXTERNAL_API_ERROR if retry_able else ErrorType.DOES_NOT_EXIST,
                retry_able=retry_able,
            )

        if response.status_code == 206:
            return Probe(_total_from_content_range(response.headers.get("content-range")), True, resolved)

        length = response.headers.get("content-length")
        return Probe(int(length) if length and length.isdigit() else None, False, resolved)
