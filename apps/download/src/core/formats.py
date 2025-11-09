import uuid
from datetime import UTC, date, datetime, time
from enum import Enum
from typing import Any
from urllib.parse import unquote, urlsplit, urlunsplit

from pydantic import BaseModel, HttpUrl, RedisDsn, SecretStr, ValidationError, WebsocketUrl


def exclude_empty(data: dict) -> dict:
    return {k: v for k, v in data.items() if v not in (None, "", (), [], {})}


def utc_iso_timestamp() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def serialize(obj: Any, instructions: dict[type, type] | None = None) -> Any:
    if instructions:
        for key, value in instructions.items():
            if isinstance(obj, key):
                return value(obj)

    if isinstance(obj, HttpUrl | RedisDsn | WebsocketUrl):
        return str(obj).strip("/")
    elif isinstance(obj, BaseModel):
        return serialize(obj.model_dump())

    elif isinstance(obj, dict):
        return {key: serialize(value) for key, value in obj.items()}

    elif isinstance(obj, list | tuple | set):
        return [serialize(item) for item in obj]

    elif isinstance(obj, Enum):
        return obj.value

    elif isinstance(obj, datetime | date | time):
        return obj.isoformat()

    elif isinstance(obj, uuid.UUID | SecretStr):
        return str(obj)

    elif hasattr(obj, "__dict__"):
        # For ORM objects like Tortoise, SQLAlchemy, etc.
        return serialize(obj.__dict__)

    elif isinstance(obj, str | int | float | bool) or obj is None:
        return obj  # Native JSON types

    else:
        raise TypeError(f"Object of type {type(obj)} is not serializable")

def clean_url(url: str | HttpUrl) -> HttpUrl:
    # Convert HttpUrl → str if needed
    url = str(url)

    # Strip whitespace & invisible chars
    url = url.strip()
    url = "".join(ch for ch in url if ch.isprintable())

    # Decode %-encodings like %E2%81%A0 or %20
    url = unquote(url)

    # Normalize components
    parsed = urlsplit(url)
    clean = urlunsplit((parsed.scheme, parsed.netloc, parsed.path, parsed.query, parsed.fragment))

    # Validate and return as HttpUrl
    try:
        return HttpUrl(clean)  # Pydantic v2
    except ValidationError as e:
        raise ValueError(f"Invalid URL after cleaning: {clean}") from e