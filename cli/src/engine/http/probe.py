import re
from dataclasses import dataclass
from email.message import Message
from pathlib import PurePosixPath
from urllib.parse import unquote, urlsplit

import httpx

from src.core.errors import DownloadError
from src.core.names import clean_name

HEADERS = {"Accept-Encoding": "identity"}


@dataclass(frozen=True)
class ProbeResult:
    final_url: str
    size: int | None
    ranges: bool
    etag: str | None
    last_modified: str | None
    name: str


def safe_url(url: str) -> str:
    """The URL without credentials, for messages."""
    parts = urlsplit(url)
    host = parts.hostname or ""
    if parts.port:
        host = f"{host}:{parts.port}"
    return parts._replace(netloc=host).geturl()


def _name(headers: httpx.Headers, final_url: str) -> str:
    raw = None
    disposition = headers.get("content-disposition")
    if disposition:
        msg = Message()
        msg["content-disposition"] = disposition
        raw = msg.get_filename()
    if not raw:
        raw = unquote(PurePosixPath(urlsplit(final_url).path).name)
    return clean_name(raw)


def _int(value: str | None) -> int | None:
    return int(value) if value is not None and value.isdigit() else None


async def probe(client: httpx.AsyncClient, url: str) -> ProbeResult:
    """HEAD the URL; when that fails, GET its first byte. Follows redirects."""
    try:
        head = await client.head(url, headers=HEADERS)
        if head.status_code < 400:
            return ProbeResult(
                final_url=str(head.url),
                size=_int(head.headers.get("content-length")),
                ranges=head.headers.get("accept-ranges", "").lower() == "bytes",
                etag=head.headers.get("etag"),
                last_modified=head.headers.get("last-modified"),
                name=_name(head.headers, str(head.url)),
            )
    except httpx.HTTPError:
        pass
    try:
        async with client.stream("GET", url, headers={**HEADERS, "Range": "bytes=0-0"}) as resp:
            if resp.status_code >= 400:
                raise DownloadError(f"HTTP {resp.status_code} for {safe_url(url)}")
            size, ranges = _int(resp.headers.get("content-length")), False
            if resp.status_code == 206:
                m = re.fullmatch(r"bytes 0-0/(\d+)", resp.headers.get("content-range", ""))
                size, ranges = (int(m.group(1)) if m else None), m is not None
            return ProbeResult(
                final_url=str(resp.url),
                size=size,
                ranges=ranges,
                etag=resp.headers.get("etag"),
                last_modified=resp.headers.get("last-modified"),
                name=_name(resp.headers, str(resp.url)),
            )
    except httpx.HTTPError as e:
        raise DownloadError(f"cannot reach {safe_url(url)}: {e}") from e
