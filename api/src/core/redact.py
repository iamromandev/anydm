"""Keeping an API key out of anything that gets logged.

Its own module because both the auth gate and the error handlers need it, and
the auth gate already imports the error module.
"""

from __future__ import annotations

import re

QUERY_PARAM = "api_key"

_QUERY_VALUE = re.compile(rf"({QUERY_PARAM}=)[^&\s\"]*")


def redact(text: str) -> str:
    """Hide a query-string key's value, leaving the rest of the URL readable."""
    return _QUERY_VALUE.sub(r"\1***", text)
