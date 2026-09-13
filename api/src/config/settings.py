from __future__ import annotations

from enum import StrEnum
from functools import lru_cache
from typing import Annotated

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Env(StrEnum):
    LOCAL = "local"
    DEV = "dev"
    PROD = "prod"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")
    # core
    env: Annotated[Env, Field(description="Application environment")]
    debug: Annotated[bool, Field(description="Enable debug mode")]
    log_format: Annotated[str, Field(default="text", description="text | json")]
    allowed_origins: Annotated[
        str,
        Field(default="", description="Comma-separated CORS origins allowed to call this API"),
    ]
    # db
    db_schema: Annotated[str, Field(default="postgresql", description="Database dialect (PostgreSQL only)")]
    db_host: Annotated[str, Field(description="Database host")]
    db_port: Annotated[int, Field(description="Database port")]
    db_name: Annotated[str, Field(description="Database name")]
    db_user: Annotated[str, Field(description="Database user")]
    db_password: Annotated[SecretStr, Field(description="Database password")]
    # downloads
    downloads_dir: Annotated[str, Field(default="./downloads", description="Where completed files land")]
    download_workers: Annotated[int, Field(default=2, ge=1, description="Concurrent download workers")]
    download_chunk_size: Annotated[int, Field(default=1048576, ge=1024, description="Read chunk size in bytes")]
    progress_flush_ms: Annotated[int, Field(default=1000, ge=100, description="How often progress reaches the DB")]
    max_attempts: Annotated[int, Field(default=3, ge=1, description="Total tries per task, including the first")]
    ffmpeg_path: Annotated[str, Field(default="ffmpeg", description="ffmpeg executable")]

    @property
    def is_local(self) -> bool:
        return self.env == Env.LOCAL

    @property
    def origins(self) -> list[str]:
        """``allowed_origins`` as a list, trimmed, with blanks dropped."""
        return [origin.strip() for origin in self.allowed_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
