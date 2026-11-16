from pathlib import Path

import pytest
from src.config.settings import get_settings
from src.core.errors import UsageError


@pytest.fixture(autouse=True)
def _isolated(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Empty cwd and config folder, no ANYDM_* variables."""
    cwd = tmp_path / "cwd"
    cwd.mkdir()
    monkeypatch.chdir(cwd)
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    monkeypatch.delenv("ANYDM_CONNECTIONS", raising=False)
    monkeypatch.delenv("ANYDM_DOWNLOAD_DIR", raising=False)
    monkeypatch.delenv("ANYDM_MAX_ACTIVE", raising=False)
    monkeypatch.delenv("ANYDM_COOKIES_FILE", raising=False)
    return tmp_path


def _write_config(root: Path, text: str) -> None:
    folder = root / "xdg" / "anydm"
    folder.mkdir(parents=True)
    (folder / "config.toml").write_text(text)


def test_defaults(_isolated: Path) -> None:
    s = get_settings()
    assert s.connections == 4
    assert s.download_dir == _isolated / "home" / "Downloads"


def test_order_flag_over_env_over_dotenv_over_config_over_default(
    _isolated: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _write_config(_isolated, "connections = 2\n")
    assert get_settings().connections == 2
    Path(".env").write_text("ANYDM_CONNECTIONS=6\n")
    assert get_settings().connections == 6
    monkeypatch.setenv("ANYDM_CONNECTIONS", "3")
    assert get_settings().connections == 3
    assert get_settings(connections=5).connections == 5


def test_none_flag_does_not_override(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANYDM_CONNECTIONS", "3")
    assert get_settings(connections=None, download_dir=None).connections == 3


def test_download_dir_from_config_and_env(_isolated: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _write_config(_isolated, f'download_dir = "{_isolated}/from-config"\n')
    assert get_settings().download_dir == _isolated / "from-config"
    monkeypatch.setenv("ANYDM_DOWNLOAD_DIR", str(_isolated / "from-env"))
    assert get_settings().download_dir == _isolated / "from-env"


def test_config_falls_back_to_home_dot_config(_isolated: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("XDG_CONFIG_HOME")
    folder = _isolated / "home" / ".config" / "anydm"
    folder.mkdir(parents=True)
    (folder / "config.toml").write_text("connections = 7\n")
    assert get_settings().connections == 7


@pytest.mark.parametrize("value", [0, 17, 99])
def test_invalid_connections_is_a_usage_error_naming_the_field(value: int) -> None:
    with pytest.raises(UsageError, match="connections"):
        get_settings(connections=value)


def test_invalid_env_value_is_a_usage_error(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANYDM_CONNECTIONS", "many")
    with pytest.raises(UsageError, match="connections"):
        get_settings()


def test_invalid_config_toml_is_a_usage_error_naming_the_file(_isolated: Path) -> None:
    _write_config(_isolated, "connections = = 2\n")
    with pytest.raises(UsageError, match=r"invalid config .*config\.toml"):
        get_settings()


def test_download_dir_expands_home(monkeypatch: pytest.MonkeyPatch, _isolated: Path) -> None:
    monkeypatch.setenv("ANYDM_DOWNLOAD_DIR", "~/dl")
    assert get_settings().download_dir == _isolated / "home" / "dl"
    assert get_settings(download_dir=Path("~/x")).download_dir == _isolated / "home" / "x"


def test_max_active_defaults_to_3_and_reads_env_and_config(_isolated: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    assert get_settings().max_active == 3
    _write_config(_isolated, "max_active = 5\n")
    assert get_settings().max_active == 5
    monkeypatch.setenv("ANYDM_MAX_ACTIVE", "2")
    assert get_settings().max_active == 2


@pytest.mark.parametrize("value", ["0", "11", "many"])
def test_invalid_max_active_names_the_field(value: str, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANYDM_MAX_ACTIVE", value)
    with pytest.raises(UsageError, match="max_active"):
        get_settings()


def test_media_image_and_docker_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANYDM_MEDIA_IMAGE", raising=False)
    monkeypatch.delenv("ANYDM_DOCKER", raising=False)
    s = get_settings()
    assert s.media_image == "anydm-media:0.0.1"
    assert s.docker == "docker"


def test_media_image_and_docker_come_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANYDM_MEDIA_IMAGE", "ghcr.io/x/anydm-media:1")
    monkeypatch.setenv("ANYDM_DOCKER", "/opt/bin/podman")
    s = get_settings()
    assert s.media_image == "ghcr.io/x/anydm-media:1"
    assert s.docker == "/opt/bin/podman"


def test_dotenv_never_sets_the_docker_command_or_the_media_image(
    _isolated: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("ANYDM_MEDIA_IMAGE", raising=False)
    monkeypatch.delenv("ANYDM_DOCKER", raising=False)
    Path(".env").write_text("ANYDM_DOCKER=/tmp/evil\nANYDM_MEDIA_IMAGE=evil:1\nANYDM_CONNECTIONS=6\n")
    s = get_settings()
    assert s.docker == "docker"
    assert s.media_image == "anydm-media:0.0.1"
    assert s.connections == 6  # .env still works for the other settings


def test_media_image_and_docker_come_from_config_toml(_isolated: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANYDM_MEDIA_IMAGE", raising=False)
    monkeypatch.delenv("ANYDM_DOCKER", raising=False)
    _write_config(_isolated, 'media_image = "ghcr.io/x/anydm-media:2"\ndocker = "podman"\n')
    s = get_settings()
    assert (s.media_image, s.docker) == ("ghcr.io/x/anydm-media:2", "podman")


@pytest.mark.parametrize(
    "image",
    ["-v/x:/y", "--privileged", "a b", "a;b", "", "$(id)", "x\ny", "UPPER/ok:1 ", "a" * 300],
)
def test_an_invalid_media_image_is_a_usage_error(image: str, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANYDM_MEDIA_IMAGE", image)
    with pytest.raises(UsageError, match="media_image"):
        get_settings()


@pytest.mark.parametrize(
    "image",
    ["anydm-media:0.0.1", "ghcr.io/iamromandev/anydm-media:1", "localhost:5000/a/b_c.d-e:tag",
     "ghcr.io/x/y@sha256:" + "a" * 64, "ghcr.io/x/y:1@sha256:" + "b" * 64],
)
def test_valid_media_image_references_are_accepted(image: str, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANYDM_MEDIA_IMAGE", image)
    assert get_settings().media_image == image


def test_cookies_file_is_not_set_by_default() -> None:
    assert get_settings().cookies_file is None


def test_cookies_file_order_flag_over_env_over_config_and_dotenv_is_ignored(
    _isolated: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    Path(".env").write_text("ANYDM_COOKIES_FILE=/from/dotenv.txt\n")
    assert get_settings().cookies_file is None
    _write_config(_isolated, 'cookies_file = "/from/config.txt"\n')
    assert get_settings().cookies_file == Path("/from/config.txt")
    monkeypatch.setenv("ANYDM_COOKIES_FILE", "/from/env.txt")
    assert get_settings().cookies_file == Path("/from/env.txt")
    assert get_settings(cookies_file=Path("/from/flag.txt")).cookies_file == Path("/from/flag.txt")


def test_cookies_file_expands_home(_isolated: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANYDM_COOKIES_FILE", "~/c.txt")
    assert get_settings().cookies_file == _isolated / "home" / "c.txt"
    assert get_settings(cookies_file=Path("~/d.txt")).cookies_file == _isolated / "home" / "d.txt"


def test_an_empty_cookies_file_env_counts_as_unset(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANYDM_COOKIES_FILE", "")
    assert get_settings().cookies_file is None


def test_an_empty_cookies_file_in_config_toml_counts_as_unset(_isolated: Path) -> None:
    _write_config(_isolated, 'cookies_file = ""\n')
    assert get_settings().cookies_file is None
