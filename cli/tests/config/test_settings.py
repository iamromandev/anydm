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
