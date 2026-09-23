"""An optional shared key in front of every route but the health check.

Unset, nothing changes: the default is still a single person on their own
machine. Set, every request must carry the key in ``X-API-Key``.

A query parameter is accepted only on the routes a browser opens without a way
to add a header: ``EventSource`` streams, file downloads started by navigation,
and the HLS URLs a ``<video>`` element fetches itself. Anywhere else a key in
the URL would be a key in history, logs and Referer headers for no reason.
"""

from __future__ import annotations

import hmac
import logging
from urllib.parse import quote

from fastapi import Request

from src.config import get_settings
from src.core.error import Error
from src.core.redact import QUERY_PARAM, redact

HEADER = "X-API-Key"

#: Route templates, not concrete paths, so a task id never needs matching.
QUERY_ROUTES = frozenset(
    {
        "/download/events",
        "/stream/events",
        "/download/{task_id}/file",
        "/download/{task_id}/file/{file_index}",
        "/stream/{session_id}/playlist.m3u8",
        "/stream/{session_id}/segment_{index}.ts",
    }
)


def expected_key() -> str:
    """The configured key, or ``""`` when none is. An empty ``API_KEY=`` is none."""
    secret = get_settings().api_key
    return secret.get_secret_value() if secret is not None else ""


def key_matches(expected: str, given: str | None) -> bool:
    """Constant time, so a wrong guess does not reveal how much of it was right.

    Compared as bytes: ``compare_digest`` refuses a ``str`` that is not ASCII,
    and a raised error is not the same answer as "no".
    """
    if not given:
        return False
    return hmac.compare_digest(expected.encode(), given.encode())


def query_key(request: Request) -> str | None:
    """The key from the query, where this route accepts one and it is right.

    Checked here as well as in ``require_api_key`` because a request whose
    header passed may still carry a wrong key in its query, and a caller that
    copies this value onward must not copy that.
    """
    route = request.scope.get("route")
    if getattr(route, "path", None) not in QUERY_ROUTES:
        return None
    given = request.query_params.get(QUERY_PARAM)
    return given if key_matches(expected_key(), given) else None


def with_segment_key(playlist: str, key: str) -> str:
    """Append the key to every URI line of an HLS playlist."""
    suffix = f"?{QUERY_PARAM}={quote(key, safe='')}"
    return "".join(
        line if not line.strip() or line.startswith("#") else line.rstrip("\n") + suffix + "\n"
        for line in playlist.splitlines(keepends=True)
    )


async def require_api_key(request: Request) -> None:
    """A dependency on every router but health's."""
    expected = expected_key()
    if not expected:
        return
    if not key_matches(expected, request.headers.get(HEADER)) and query_key(request) is None:
        raise Error.unauthorized("A valid API key is required")


class RedactApiKey(logging.Filter):
    """Keeps a query-string key out of uvicorn's access log.

    The access line is built from ``record.args`` at format time, so the path
    is rewritten there rather than in the finished message.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.args, tuple):
            record.args = tuple(redact(arg) if isinstance(arg, str) else arg for arg in record.args)
        return True
