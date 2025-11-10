import hashlib
import json
import re
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, TypeVar
from urllib.parse import urlparse

import toml
from pydantic import HttpUrl

from src.core.format import serialize

K = TypeVar("K")
V = TypeVar("V")
T = TypeVar("T")
VarTuple = tuple[V] | tuple[V, V] | tuple[V, V, V]


def get_app_version() -> str:
    try:
        with open("pyproject.toml") as f:
            data = toml.load(f)
            return data['project']['version']
    except FileNotFoundError:
        return "Version information not found"
    except KeyError:
        return "Version key not found in pyproject.toml"


def get_base_url(url: HttpUrl) -> HttpUrl:
    """
    Extracts the base URL from a given URL.

    Parameters:
        url (HttpUrl): The full URL.

    Returns:
        HttpUrl: The base URL (scheme + domain).
    """
    parsed_url = urlparse(serialize(url))
    return HttpUrl.build(
        scheme=parsed_url.scheme,
        host=parsed_url.hostname,  # hostname only, no port
        port=parsed_url.port,  # optional
        path=""  # empty path
    )


def get_path(url: HttpUrl) -> str:
    """
    Extracts the path and query string from a given URL.

    Parameters:
        url (HttpUrl): The full URL.

    Returns:
        str: The path and query portion of the URL.
    """
    parsed_url = urlparse(serialize(url))
    path = parsed_url.path or "/"
    if parsed_url.query:
        path += f"?{parsed_url.query}"
    return path


def get_file_extension_with_dot(filename: str) -> str | None:
    ext = Path(filename).suffix
    return ext


def get_file_extension(filename: str) -> str | None:
    ext = Path(filename).suffix
    file_extension = ext.lstrip('.') if ext else None
    return file_extension


def current_timestamp(format: str | None = None) -> str:
    # "%Y%m%d%H%M%S"
    dt = datetime.now(UTC)
    timestamp = dt.strftime(format) if format else dt.isoformat()
    return timestamp


def safely_deep_get(
    data: Mapping[K, V] | Sequence[V] | object,
    keys: str,
    default: T | None = None,
) -> Any | None:
    """
    Returns a value from nested dictionary/list/tuple/object using dot-separated keys.

    Args:
        data: Nested dictionary, list/tuple, or object.
        keys: Dot-separated keys, e.g., "user.profile.name".
        default: Value to return if key not found. Defaults to None.

    Returns:
        The value at the nested key path or `default` if not found.
    """
    node = data
    for key in keys.split("."):
        if isinstance(node, dict):
            node = node.get(key, None)
        elif isinstance(node, (list, tuple)) and key.isdigit():
            index = int(key)
            node = node[index] if 0 <= index < len(node) else None
        elif hasattr(node, key):
            node = getattr(node, key)
        else:
            return default

        if node is None:
            return default

    return node


def compute_checksum(content: dict[str, Any]) -> str:
    content_str: str = json.dumps(content, sort_keys=True)
    content_bytes: bytes = content_str.encode('utf-8')
    return hashlib.sha256(content_bytes).hexdigest()


def safe_filename(title: str, ext: str = "mp4") -> Path:
    """
    Returns a filesystem-safe filename from a video title.
    Removes illegal characters and trims excessive length.
    """
    # Replace invalid characters (Windows, Linux, macOS)
    safe_title = re.sub(r'[<>:"/\\|?*\x00-\x1F]', "_", title)
    safe_title = re.sub(r"\s+", " ", safe_title).strip()  # normalize spaces

    # Limit filename length to 150 chars to avoid OS limits
    safe_title = safe_title[:150]

    return Path(f"{safe_title}.{ext}")
