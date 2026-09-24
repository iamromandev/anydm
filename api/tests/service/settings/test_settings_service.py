"""What the settings endpoint is allowed to say.

The point of this endpoint is to save someone reading `api/.env` over SSH. The
point of this test is that it never saves them reading the password too.
"""

import tomllib
from pathlib import Path

from src.config import get_settings
from src.service.settings import SettingsService

#: Anything whose name contains one of these has no business leaving the
#: process. Checked against the response's keys and its values, because a
#: password is just as leaked when it is the value of a harmless-looking key.
FORBIDDEN = ("password", "secret", "token", "key", "dsn", "cors")


def test_the_response_names_nothing_sensitive() -> None:
    data = SettingsService().describe().to_dict()

    for name in data:
        assert not any(word in name.lower() for word in FORBIDDEN), name


def test_the_response_carries_no_database_credentials() -> None:
    settings = get_settings()
    data = SettingsService().describe().to_dict()
    values = {str(value) for value in data.values()}

    assert settings.db_password.get_secret_value() not in values
    assert settings.db_user not in values
    assert settings.db_host not in values


def test_it_reports_what_someone_would_otherwise_ssh_for() -> None:
    settings = get_settings()
    described = SettingsService().describe()

    assert described.download_workers == settings.download_workers
    assert described.download_segments == settings.download_segments
    assert described.torrent_enabled == settings.torrent_enabled
    assert described.version


def test_every_field_is_named_one_by_one() -> None:
    """Built from an allow-list, never by walking the settings object.

    A response assembled by iteration grows a new field every time settings
    do, which is how a secret gets published by accident.
    """
    import inspect

    from src.service.settings import settings_service

    source = inspect.getsource(settings_service)
    assert "model_dump" not in source
    assert "__dict__" not in source


def test_it_reports_every_rate_limit() -> None:
    settings = get_settings()
    described = SettingsService().describe()

    assert described.download_rate_limit_bps == settings.download_rate_limit_bps
    assert described.torrent_download_limit_bps == settings.torrent_download_limit_bps
    assert described.torrent_upload_limit_bps == settings.torrent_upload_limit_bps


def test_it_reports_the_disk_space_floor() -> None:
    settings = get_settings()

    assert SettingsService().describe().download_min_free_bytes == settings.download_min_free_bytes


def test_it_reports_the_yt_dlp_version_that_is_pinned() -> None:
    # Sites break when yt-dlp falls behind, so the first question is which
    # one is running. It should be the pin: the lock installs exactly that.
    pyproject = tomllib.loads((Path(__file__).parents[3] / "pyproject.toml").read_text())
    pinned = next(
        dependency.split("==", 1)[1]
        for dependency in pyproject["project"]["dependencies"]
        # The package's name, whatever extras follow it.
        if dependency.split("==", 1)[0].split("[", 1)[0] == "yt-dlp"
    )

    assert SettingsService().describe().yt_dlp_version == pinned
