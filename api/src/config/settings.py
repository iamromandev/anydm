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
    download_chunk_size: Annotated[
        int,
        Field(default=65536, ge=1024, description="Read chunk size in bytes"),
    ]
    download_segments: Annotated[
        int,
        Field(default=4, ge=1, description="Concurrent range requests per part"),
    ]
    download_segment_min_bytes: Annotated[
        int,
        Field(default=16777216, ge=0, description="Smallest file worth splitting"),
    ]
    download_write_buffer_bytes: Annotated[
        int,
        Field(default=4194304, ge=65536, description="Bytes buffered before a positional write"),
    ]
    download_progress_flush_ms: Annotated[
        int,
        Field(default=1000, ge=100, description="How often progress reaches the DB"),
    ]
    download_max_attempts: Annotated[
        int,
        Field(default=3, ge=1, description="Total tries per task, including the first"),
    ]
    # torrent
    torrent_enabled: Annotated[
        bool,
        Field(default=True, description="Enable torrent routes and the monitor"),
    ]
    torrent_api_url: Annotated[
        str,
        Field(
            default="http://torrent-anydm-api:3030",
            description="rqbit control API base URL",
        ),
    ]
    torrent_dir: Annotated[
        str,
        Field(default="./download/torrent", description="Output folder handed to rqbit"),
    ]
    torrent_poll_ms: Annotated[
        int,
        Field(default=1000, ge=250, description="Monitor tick in milliseconds"),
    ]
    torrent_metadata_timeout_s: Annotated[
        int,
        Field(default=30, ge=1, description="How long resolve waits for peers to supply metadata"),
    ]
    torrent_request_timeout_s: Annotated[
        int,
        Field(default=10, ge=1, description="Per-call timeout against the control API"),
    ]
    # media
    ffmpeg_path: Annotated[str, Field(default="ffmpeg", description="ffmpeg executable")]

    @property
    def is_local(self) -> bool:
        return self.env == Env.LOCAL

    @property
    def http_max_connections(self) -> int:
        """Every socket the pool can be asked for at once, plus headroom.

        Derived rather than configured: httpx silently queues requests past its
        limit, so a pool smaller than ``workers x segments`` would serialise the
        segments it was added to parallelise, and nothing would report an error.
        """
        return self.download_workers * self.download_segments + 4

    @property
    def http_max_keepalive(self) -> int:
        return self.download_workers * self.download_segments

    @property
    def cors_origin_list(self) -> list[str]:
        """``cors_origins`` as a list, trimmed, with blanks dropped."""
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
