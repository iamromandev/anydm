import re
import unicodedata
import uuid
from collections.abc import Iterable
from datetime import UTC, date, datetime, time
from enum import Enum
from pathlib import Path
from typing import Any, cast
from urllib.parse import urlparse

import toml
from pydantic import BaseModel, BeforeValidator, EmailStr, HttpUrl, SecretStr


def now() -> datetime:
    return datetime.now(UTC)


def host_of(url: str) -> str:
    """The host and optional port of ``url``, without scheme or path."""
    candidate = (url or "").strip()
    if not candidate:
        return ""
    # urlparse reads a schemeless "localhost:8000" as scheme "localhost" with
    # path "8000". A leading "//" forces it to parse the whole thing as an
    # authority, so a bare host with a port survives.
    if "://" not in candidate:
        candidate = f"//{candidate}"
    return urlparse(candidate).netloc


def origin_of(url: str) -> str:
    """The scheme and host of ``url``, or "" when it carries neither."""
    parsed = urlparse(url)
    if not parsed.scheme or not parsed.netloc:
        return ""
    return f"{parsed.scheme}://{parsed.netloc}"


def slugify(value: str) -> str:
    """A URL- and handle-safe form of ``value`` — 'Acme Corp!' becomes 'acme-corp'.

    Lowercase, every run of characters that is not a letter or digit collapsed
    to a single hyphen, and no hyphen left at either end. Accented letters fold
    to their base form first, so 'Ünïcode' slugs as 'unicode' rather than losing
    the letters that carry the marks.
    """
    folded = unicodedata.normalize("NFKD", (value or "").strip().lower())
    ascii_only = folded.encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", ascii_only).strip("-")


def blank_as(default: str) -> BeforeValidator:
    """Treat a blank or absent value as unset, so ``default`` applies."""

    def coerce(v: Any) -> Any:
        if v is None:
            return default
        if not isinstance(v, str):
            return v
        return v.strip() or default

    return BeforeValidator(coerce)


def blank_as_none(v: Any) -> Any:
    """Collapse a blank string to ``None``.

    Distinct from ``blank_as``: a field that means "resolve this for me" needs
    the empty form a browser sends to arrive as absent, not as "".
    """
    if not isinstance(v, str):
        return v
    return v.strip() or None


def as_list[T](value: T | Iterable[T]) -> list[T]:
    """Wrap a single value (a str counts as single) in a list; pass any other iterable through as a list."""
    if isinstance(value, str) or not isinstance(value, Iterable):
        return [cast(T, value)]
    return list(cast(Iterable[T], value))


def get_app_version() -> str:
    try:
        with open("pyproject.toml") as f:
            data = toml.load(f)
            return data['project']['version']
    except FileNotFoundError:
        return "Version information not found"
    except KeyError:
        return "Version key not found in pyproject.toml"


def serialize(
    obj: Any,
    instructions: dict[type, type] | None = None,
    strip: bool = False
) -> Any:
    if instructions:
        for key, value in instructions.items():
            if isinstance(obj, key):
                return value(obj)

    if isinstance(obj, EmailStr | HttpUrl):  # ty: ignore[invalid-argument-type] - EmailStr is a runtime class
        return str(obj).strip("/") if strip else str(obj)

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

    elif isinstance(obj, uuid.UUID):
        return str(obj)

    elif isinstance(obj, SecretStr):
        return obj.get_secret_value()

    elif isinstance(obj, str):
        return obj

    elif hasattr(obj, "__dict__"):
        # For ORM objects like Tortoise, SQLAlchemy, etc.
        return serialize(obj.__dict__)

    elif isinstance(obj, int | float | bool) or obj is None:
        return obj  # Native JSON type

    else:
        raise TypeError(f"Object of type {type(obj)} is not serializable")


def app_path(path_name: str | None = None) -> str:
    the_path = str(Path(__file__).parent.parent)

    if path_name:
        the_path = f"{the_path}/{path_name}"

    return the_path
