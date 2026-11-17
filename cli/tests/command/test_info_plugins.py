"""`anydm info` with installed plugins."""

import json
from pathlib import Path

import pytest
from src import engine
from src.main import app
from src.plugin import state
from src.plugin.model import Plugin
from typer.testing import CliRunner

from tests.conftest import FakeDocker

runner = CliRunner()
HELLO = {"type": "hello", "protocol": 3}
A_IMAGE = "ghcr.io/acme/a@sha256:" + "a" * 64
B_IMAGE = "ghcr.io/acme/b@sha256:" + "b" * 64
URL = "https://example.org/thing/1"
ALL = frozenset({"check", "download", "info"})
INFO = {"type": "info", "title": "A thing", "size": 2048, "formats": [{"id": "hd", "ext": "mp4", "note": "720p"}],
        "items": []}


@pytest.fixture(autouse=True)
def _env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    monkeypatch.delenv("ANYDM_COOKIES_FILE", raising=False)
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)


@pytest.fixture
def docker(fake_docker: FakeDocker) -> FakeDocker:
    state.save([Plugin("alpha", A_IMAGE, "1", ALL, frozenset({"network"})),
                Plugin("beta", B_IMAGE, "1", ALL, frozenset({"network", "cookies"}))])
    fake_docker.set(plugins={
        A_IMAGE: {"check": False, "info_lines": [HELLO, {**INFO, "title": "from alpha"}]},
        B_IMAGE: {"check": True, "info_lines": [HELLO, {**INFO, "title": "from beta"}]},
    })
    return fake_docker


def info_calls(docker: FakeDocker) -> list[list[str]]:
    return [c for c in docker.calls() if c[0] == "run" and "info" in c[c.index("--") + 1:]]


def mounts(call: list[str]) -> list[str]:
    return [call[i + 1] for i, a in enumerate(call) if a == "--mount"]


def cookie_file(folder: Path) -> Path:
    f = folder / "cookies.txt"
    f.write_text("# Netscape HTTP Cookie File\n.example.org\tTRUE\t/\tFALSE\t2000000000\tsid\tvalue\n")
    f.chmod(0o600)
    return f


def test_info_is_asked_of_the_plugin_that_claims_the_link(docker: FakeDocker) -> None:
    result = runner.invoke(app, ["info", URL])
    assert result.exit_code == 0, result.stderr
    assert "from beta" in result.stdout and "2.0 KiB" in result.stdout and "720p" in result.stdout
    [call] = info_calls(docker)
    assert call[call.index("--") + 1] == B_IMAGE
    assert "-v" not in call


def test_info_json_names_the_plugin(docker: FakeDocker) -> None:
    result = runner.invoke(app, ["info", URL, "--json"])
    assert json.loads(result.stdout) == {
        "kind": "plugin", "plugin": "beta", "title": "from beta", "size": 2048,
        "formats": [{"id": "hd", "ext": "mp4", "note": "720p"}], "items": [],
    }


def test_plugin_flag_skips_check(docker: FakeDocker) -> None:
    result = runner.invoke(app, ["info", URL, "--plugin", "alpha"])
    assert result.exit_code == 0, result.stderr
    assert "from alpha" in result.stdout
    assert [c for c in docker.calls() if c[0] == "run" and "check" in c[c.index("--") + 1:]] == []


def test_cookies_are_mounted_only_for_a_plugin_with_the_permission(docker: FakeDocker, tmp_path: Path) -> None:
    cookies = cookie_file(tmp_path)
    runner.invoke(app, ["info", URL, "--plugin", "beta", "--cookies", str(cookies)])
    runner.invoke(app, ["info", URL, "--plugin", "alpha", "--cookies", str(cookies)])
    beta, alpha = info_calls(docker)
    assert mounts(beta) == [f"type=bind,src={cookies.resolve()},dst=/run/anydm/cookies.txt,readonly"]
    assert beta[-1] == "--cookies"
    assert mounts(alpha) == [] and "--cookies" not in alpha


def test_a_bad_cookies_file_only_matters_to_a_plugin_with_the_permission(docker: FakeDocker, tmp_path: Path) -> None:
    bad = str(tmp_path / "missing.txt")
    assert runner.invoke(app, ["info", URL, "--plugin", "alpha", "--cookies", bad]).exit_code == 0
    result = runner.invoke(app, ["info", URL, "--plugin", "beta", "--cookies", bad])
    assert result.exit_code == 2 and "missing.txt" in result.stderr


def test_an_unknown_plugin_exits_2(docker: FakeDocker) -> None:
    result = runner.invoke(app, ["info", URL, "--plugin", "nope"])
    assert result.exit_code == 2 and "no plugin named" in result.stderr


def test_a_plugin_without_info_chosen_by_name_exits_2(fake_docker: FakeDocker) -> None:
    state.save([Plugin("quiet", A_IMAGE, "1", frozenset({"check", "download"}), frozenset())])
    result = runner.invoke(app, ["info", URL, "--plugin", "quiet"])
    assert result.exit_code == 2 and "plugin quiet cannot show info" in result.stderr


def test_a_plugin_without_info_is_not_asked_and_the_next_one_that_claims_the_link_answers(
    fake_docker: FakeDocker,
) -> None:
    quiet = "ghcr.io/acme/q@sha256:" + "c" * 64
    state.save([Plugin("quiet", quiet, "1", frozenset({"check", "download"}), frozenset()),
                Plugin("beta", B_IMAGE, "1", ALL, frozenset({"network"}))])
    fake_docker.set(plugins={
        quiet: {"check": True},
        B_IMAGE: {"check": True, "info_lines": [HELLO, {**INFO, "title": "from beta"}]},
    })
    result = runner.invoke(app, ["info", URL])
    assert result.exit_code == 0, result.stderr
    assert "from beta" in result.stdout
    asked = [c[c.index("--") + 1] for c in fake_docker.calls() if c[0] == "run" and "check" in c[c.index("--") + 1:]]
    assert asked == [B_IMAGE]


def test_a_plugin_without_cookies_permission_says_the_cookies_were_not_passed(
    docker: FakeDocker, tmp_path: Path
) -> None:
    cookies = cookie_file(tmp_path)
    result = runner.invoke(app, ["info", URL, "--plugin", "alpha", "--cookies", str(cookies)])
    assert result.exit_code == 0, result.stderr
    assert "cookies were not passed to plugin alpha" in result.stderr


def test_plugin_flag_without_docker_exits_1_naming_docker(docker: FakeDocker) -> None:
    docker.set(version="fail")
    result = runner.invoke(app, ["info", URL, "--plugin", "alpha"])
    assert result.exit_code == 1 and "Docker" in result.stderr and "anydm doctor" in result.stderr


def test_an_error_line_from_the_plugin_exits_1(docker: FakeDocker) -> None:
    docker.set(plugins={A_IMAGE: {"info_lines": [HELLO, {"type": "error", "message": "no such thing", "partial": []}]}})
    result = runner.invoke(app, ["info", URL, "--plugin", "alpha"])
    assert result.exit_code == 1 and "no such thing" in result.stderr


def test_plugin_media_is_media(docker: FakeDocker) -> None:
    docker.set(info_lines=[HELLO, {**INFO, "title": "from media"}])
    result = runner.invoke(app, ["info", URL, "--plugin", "media", "--json"])
    assert result.exit_code == 0, result.stderr
    assert json.loads(result.stdout)["plugin"] == "media" and json.loads(result.stdout)["title"] == "from media"
