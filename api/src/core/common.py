import uuid
from datetime import UTC, date, datetime, time
from enum import Enum
from typing import Any

import toml
from pydantic import BaseModel, EmailStr, HttpUrl, SecretStr


def now() -> datetime:
    return datetime.now(UTC)


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

    elif isinstance(obj, int | float | bool) or obj is None:
        return obj  # Native JSON type

    else:
        raise TypeError(f"Object of type {type(obj)} is not serializable")
