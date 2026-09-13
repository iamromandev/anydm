from typing import Annotated

from pydantic import Field

from src.core.base import BaseSchema
from src.core.type import Status


class DatabaseSchema(BaseSchema):
    status: Annotated[Status, Field(default=Status.ERROR)]
    version: Annotated[str | None, Field(default=None)]


class HealthSchema(BaseSchema):
    version: Annotated[str, Field(default="0.0.1", description="Application version")] = "0.0.1"
    host: Annotated[str | None, Field(default=None, description="Address the server is listening on")] = None
    port: Annotated[int | None, Field(default=None, description="Port the server is listening on")] = None
    environment: Annotated[str | None, Field(default=None, description="Application environment")] = None
    uptime: Annotated[str | None, Field(default=None, description="Human-readable uptime (e.g. 1h 2m 3s)")] = None
    db: Annotated[DatabaseSchema | None, Field(default=None)] = None
