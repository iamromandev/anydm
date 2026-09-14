from functools import lru_cache
from typing import Annotated

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

from src.core.type import Env


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # core
    env: Annotated[Env, Field(description="Application environment")]
    debug: Annotated[bool, Field(description="Enable debug mode")]
    log_format: Annotated[str, Field(default="text", description="text | json")]
    # db
    db_engine: Annotated[
        str,
        Field(default="tortoise.backends.asyncpg", description="Tortoise database engine backend"),
    ]
    db_host: Annotated[str, Field(description="Database host")]
    db_port: Annotated[int, Field(description="Database port")]
    db_name: Annotated[str, Field(description="Database name")]
    db_user: Annotated[str, Field(description="Database user")]
    db_password: Annotated[SecretStr, Field(description="Database password")]
    # cors: comma-separated origins; empty disables CORS middleware (same-origin only)
    cors_origins: str = ""
    # public URL of this API
    public_base_url: str = "http://127.0.0.1:8030"
    # download
    download_dir: Annotated[str, Field(default="./download", description="Where completed files land")]
    download_workers: Annotated[int, Field(default=2, ge=1, description="Concurrent download workers")]
    download_chunk_size: Annotated[int, Field(default=65536, ge=1024, description="Read chunk size in bytes")]
    download_progress_flush_ms: Annotated[
        int,
        Field(default=1000, ge=100, description="How often progress reaches the DB"),
    ]
    download_max_attempts: Annotated[
        int,
        Field(default=3, ge=1, description="Total tries per task, including the first"),
    ]
    # media
    ffmpeg_path: Annotated[str, Field(default="ffmpeg", description="ffmpeg executable")]

    @property
    def is_local(self) -> bool:
        return self.env == Env.LOCAL

    @property
    def cors_origin_list(self) -> list[str]:
        """``cors_origins`` as a list, trimmed, with blanks dropped."""
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
