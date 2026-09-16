from typing import Any

import pytest
from pydantic import ValidationError
from src.config.settings import Settings


def _settings(**overrides: Any) -> Settings:
    base: dict[str, Any] = {
        "env": "local",
        "debug": True,
        "db_host": "localhost",
        "db_port": 5432,
        "db_name": "db-anydm",
        "db_user": "user",
        "db_password": "password",
    }
    base.update(overrides)
    return Settings(**base)


def test_cors_origin_list_splits_and_trims() -> None:
    settings = _settings(cors_origins=" http://a.test , http://b.test ")
    assert settings.cors_origin_list == ["http://a.test", "http://b.test"]


def test_cors_origin_list_drops_blanks() -> None:
    assert _settings(cors_origins="http://a.test,,  ,").cors_origin_list == ["http://a.test"]


def test_cors_origin_list_empty_when_unset() -> None:
    assert _settings(cors_origins="").cors_origin_list == []


def test_is_local() -> None:
    assert _settings(env="local").is_local is True
    assert _settings(env="prod").is_local is False


def test_stream_defaults() -> None:
    settings = _settings()
    assert settings.ffprobe_path == "ffprobe"
    assert settings.stream_dir == "./stream"
    assert settings.stream_segment_seconds == 6
    assert settings.stream_readahead_segments == 2
    assert settings.stream_max_concurrent_encodes == 2
    assert settings.stream_idle_timeout_s == 300


def test_download_defaults() -> None:
    settings = _settings()
    assert settings.download_dir == "./download"
    assert settings.download_workers == 2
    assert settings.download_progress_flush_ms == 1000
    assert settings.download_max_attempts == 3
    assert settings.ffmpeg_path == "ffmpeg"


def test_segment_defaults() -> None:
    settings = _settings()
    assert settings.download_segments == 4
    assert settings.download_segment_min_bytes == 16 * 1024 * 1024
    assert settings.download_write_buffer_bytes == 4 * 1024 * 1024
    # Deliberately still 64 KiB: the write buffer coalesces disk writes, but the
    # chunk is what paces progress updates, because the writer's flush timer
    # only gets a chance to fire when a chunk arrives.
    assert settings.download_chunk_size == 64 * 1024


# The pool must hold every socket the workers can open at once. httpx queues
# beyond its limit, which serialises segments and makes the whole feature look
# like it does nothing while reporting no error at all.
def test_http_pool_covers_every_worker_times_every_segment() -> None:
    settings = _settings(download_workers=3, download_segments=8)
    assert settings.http_max_connections == 3 * 8 + 4
    assert settings.http_max_keepalive == 3 * 8


def test_segments_must_be_at_least_one() -> None:
    with pytest.raises(ValidationError):
        _settings(download_segments=0)


def test_torrent_defaults() -> None:
    settings = _settings()
    assert settings.torrent_enabled is True
    assert settings.torrent_api_url == "http://torrent-anydm-api:3030"
    assert settings.torrent_dir == "./download/torrent"
    assert settings.torrent_poll_ms == 1000
    assert settings.torrent_metadata_timeout_s == 30
    assert settings.torrent_request_timeout_s == 10


def test_torrent_poll_ms_has_a_floor() -> None:
    with pytest.raises(ValidationError):
        _settings(torrent_poll_ms=100)
