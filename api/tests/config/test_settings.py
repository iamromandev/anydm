from typing import Any

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


def test_download_defaults() -> None:
    settings = _settings()
    assert settings.download_dir == "./download"
    assert settings.download_workers == 2
    assert settings.download_progress_flush_ms == 1000
    assert settings.download_max_attempts == 3
    assert settings.ffmpeg_path == "ffmpeg"
