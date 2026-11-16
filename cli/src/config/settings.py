import importlib.metadata
import os
import re
import tomllib
from pathlib import Path
from typing import Annotated, Any

from pydantic import Field, ValidationError, field_validator
from pydantic_settings import (
    BaseSettings,
    DotEnvSettingsSource,
    PydanticBaseSettingsSource,
    SettingsConfigDict,
    TomlConfigSettingsSource,
)

from src.core.errors import UsageError


def config_path() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME")
    root = Path(base) if base else Path.home() / ".config"
    return root / "anydm" / "config.toml"


# [host[:port]/]path[:tag][@digest]: starts with a letter or digit (never '-', so it cannot be read as a docker
# option), then only the characters an image reference can contain.
_IMAGE_REF = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}")
# Settings the current folder's .env must not set: they decide which program and image anydm runs.
_NOT_FROM_DOTENV = ("docker", "media_image")


class _DotEnv(DotEnvSettingsSource):
    """The .env source without the settings in _NOT_FROM_DOTENV: a downloaded folder could carry a hostile .env."""

    def __call__(self) -> dict[str, Any]:
        data = super().__call__()
        for name in _NOT_FROM_DOTENV:
            data.pop(name, None)
        return data


def default_media_image() -> str:
    """The local tag `make build-media` builds, until the image is published."""
    try:
        return f"anydm-media:{importlib.metadata.version('anydm-cli')}"
    except importlib.metadata.PackageNotFoundError:
        return "anydm-media:latest"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", env_prefix="ANYDM_", extra="ignore")

    download_dir: Annotated[Path, Field(default_factory=lambda: Path.home() / "Downloads")]
    connections: Annotated[int, Field(default=4, ge=1, le=16)]
    max_active: Annotated[int, Field(default=3, ge=1, le=10)]
    media_image: Annotated[str, Field(default_factory=default_media_image, min_length=1)]
    docker: Annotated[str, Field(default="docker", min_length=1)]

    @field_validator("download_dir")
    @classmethod
    def _expand_home(cls, v: Path) -> Path:
        return v.expanduser()

    @field_validator("media_image")
    @classmethod
    def _image_reference(cls, v: str) -> str:
        if not _IMAGE_REF.fullmatch(v):
            raise ValueError("not an image reference (letters, digits and . _ / : @ - only, not starting with '-')")
        return v

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
        return (init_settings, env_settings, _DotEnv(settings_cls), toml)


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
