import os
import tomllib
from pathlib import Path
from typing import Annotated, Any

from pydantic import Field, ValidationError, field_validator
from pydantic_settings import (
    BaseSettings,
    PydanticBaseSettingsSource,
    SettingsConfigDict,
    TomlConfigSettingsSource,
)

from src.core.errors import UsageError


def config_path() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME")
    root = Path(base) if base else Path.home() / ".config"
    return root / "anydm" / "config.toml"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", env_prefix="ANYDM_", extra="ignore")

    download_dir: Annotated[Path, Field(default_factory=lambda: Path.home() / "Downloads")]
    connections: Annotated[int, Field(default=4, ge=1, le=16)]

    @field_validator("download_dir")
    @classmethod
    def _expand_home(cls, v: Path) -> Path:
        return v.expanduser()

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        toml = TomlConfigSettingsSource(settings_cls, toml_file=config_path())
        return (init_settings, env_settings, dotenv_settings, toml)


def get_settings(**flags: Any) -> Settings:
    """Settings from flags (None means not given), env, .env, config.toml, then defaults."""
    given = {k: v for k, v in flags.items() if v is not None}
    try:
        return Settings(**given)
    except ValidationError as e:
        problems = "; ".join(f"{'.'.join(map(str, err['loc']))}: {err['msg']}" for err in e.errors())
        raise UsageError(f"invalid setting {problems}") from e
    except (tomllib.TOMLDecodeError, OSError) as e:
        raise UsageError(f"invalid config {config_path()}: {e}") from e
