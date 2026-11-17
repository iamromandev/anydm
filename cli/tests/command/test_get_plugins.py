"""`anydm get` with installed plugins: routing, --plugin, and who gets the cookies."""

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
YOUTUBE = "https://www.youtube.com/watch?v=abc"
SECRET = "s3cr3t-session-value-7f3a"
ALL = frozenset({"check", "download", "info"})


@pytest.fixture(autouse=True)
def _env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    monkeypatch.delenv("ANYDM_COOKIES_FILE", raising=False)
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)


def install(*plugins: Plugin) -> None:
    state.save(list(plugins))


@pytest.fixture
def docker(fake_docker: FakeDocker) -> FakeDocker:
    """alpha (network) says no, beta (network, cookies) says yes; both download /downloads/<name>.bin."""
    install(Plugin("alpha", A_IMAGE, "1", ALL, frozenset({"network"})),
            Plugin("beta", B_IMAGE, "1", ALL, frozenset({"network", "cookies"})))
    fake_docker.set(check=False, plugins={
        A_IMAGE: {"check": False, "lines": [HELLO, {"type": "done", "paths": ["/downloads/alpha.bin"]}]},
        B_IMAGE: {"check": True, "lines": [HELLO, {"type": "done", "paths": ["/downloads/beta.bin"]}]},
    })
    return fake_docker


def cookie_file(folder: Path, mode: int = 0o600) -> Path:
    f = folder / "cookies.txt"
    f.write_text(f"# Netscape HTTP Cookie File\n.example.org\tTRUE\t/\tFALSE\t2000000000\tsid\t{SECRET}\n")
    f.chmod(mode)
    return f


def downloads(docker: FakeDocker) -> list[list[str]]:
    return [c for c in docker.calls() if c[0] == "run" and "download" in c[c.index("--") + 1:]]


def mounts(call: list[str]) -> list[str]:
    return [call[i + 1] for i, a in enumerate(call) if a == "--mount"]


def get(*args: str, out: Path):
    return runner.invoke(app, ["get", *args, "-o", str(out), "--quiet"])


def test_a_link_is_downloaded_by_the_first_plugin_that_claims_it(docker: FakeDocker, tmp_path: Path) -> None:
    result = get(URL, out=tmp_path / "o")
    assert result.exit_code == 0, result.stderr
    assert result.stdout == f"{(tmp_path / 'o' / 'beta.bin').resolve()}\n"
    [call] = downloads(docker)
    assert call[call.index("--") + 1] == B_IMAGE and "anydm.plugin=beta" in call


def test_plugin_flag_uses_that_plugin_and_asks_nobody(docker: FakeDocker, tmp_path: Path) -> None:
    result = get(URL, "--plugin", "alpha", out=tmp_path / "o")
    assert result.exit_code == 0, result.stderr
    assert result.stdout == f"{(tmp_path / 'o' / 'alpha.bin').resolve()}\n"
    assert [c for c in docker.calls() if c[0] == "run" and "check" in c[c.index("--") + 1:]] == []
    [call] = downloads(docker)
    assert call[call.index("--") + 1] == A_IMAGE


@pytest.mark.parametrize("plugin", ["nope", "Media"])
def test_an_unknown_plugin_exits_2(docker: FakeDocker, tmp_path: Path, plugin: str) -> None:
    result = get(URL, "--plugin", plugin, out=tmp_path / "o")
    assert result.exit_code == 2
    assert "no plugin named" in result.stderr
    assert downloads(docker) == []


def test_a_plugin_that_cannot_download_exits_2(fake_docker: FakeDocker, tmp_path: Path) -> None:
    install(Plugin("lookup", A_IMAGE, "1", frozenset({"check", "info"}), frozenset()))
    result = get(URL, "--plugin", "lookup", out=tmp_path / "o")
    assert result.exit_code == 2 and "plugin lookup cannot download" in result.stderr


def test_plugin_flag_without_docker_exits_1_naming_docker_and_doctor(docker: FakeDocker, tmp_path: Path) -> None:
    docker.set(version="fail")
    result = get(URL, "--plugin", "alpha", out=tmp_path / "o")
    assert result.exit_code == 1
    assert "Docker" in result.stderr and "anydm doctor" in result.stderr


def test_without_docker_a_link_falls_back_to_http_with_one_notice(
    docker: FakeDocker, serve, tmp_path: Path
) -> None:
    docker.set(version="fail")
    srv = serve(b"abc")
    result = runner.invoke(app, ["get", srv.url(), "-o", str(tmp_path / "o")])
    assert result.exit_code == 0, result.stderr
    assert (tmp_path / "o" / "file.bin").read_bytes() == b"abc"
    assert result.stderr.count("detection skipped") == 1 and "Docker" in result.stderr


def test_kind_media_and_plugin_media_both_run_the_media_container_without_asking_anyone(
    docker: FakeDocker, tmp_path: Path
) -> None:
    docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/m.mp4"]}])  # media is not in the plugin table
    for flags in (["--kind", "media"], ["--plugin", "media"]):
        result = get(URL, *flags, out=tmp_path / "o")
        assert result.exit_code == 0, result.stderr
        assert result.stdout == f"{(tmp_path / 'o' / 'm.mp4').resolve()}\n"
    runs = [c for c in docker.calls() if c[0] == "run"]
    assert len(runs) == 2 and all("anydm=media" in c for c in runs)


def test_kind_and_plugin_together_exit_2(docker: FakeDocker, tmp_path: Path) -> None:
    result = get(URL, "--kind", "http", "--plugin", "alpha", out=tmp_path / "o")
    assert result.exit_code == 2 and "--kind and --plugin" in result.stderr


def test_a_plugin_with_the_cookies_permission_gets_the_cookies(docker: FakeDocker, tmp_path: Path) -> None:
    cookies = cookie_file(tmp_path)
    result = get(URL, "--cookies", str(cookies), out=tmp_path / "o")  # beta claims it
    assert result.exit_code == 0, result.stderr
    [call] = downloads(docker)
    assert mounts(call) == [f"type=bind,src={cookies.resolve()},dst=/run/anydm/cookies.txt,readonly"]
    assert call[-1] == "--cookies"
    check = [c for c in docker.calls() if c[0] == "run" and "check" in c[c.index("--") + 1:]]
    assert check and all("--mount" not in c and "--cookies" not in c for c in check)


def test_a_plugin_without_the_cookies_permission_never_gets_them(docker: FakeDocker, tmp_path: Path) -> None:
    cookies = cookie_file(tmp_path)
    result = get(URL, "--plugin", "alpha", "--cookies", str(cookies), out=tmp_path / "o")
    assert result.exit_code == 0, result.stderr
    [call] = downloads(docker)
    assert mounts(call) == [] and "--cookies" not in call
    assert SECRET not in "".join(" ".join(c) for c in docker.calls())


def test_a_plugin_without_the_cookies_permission_says_the_cookies_were_not_passed(
    docker: FakeDocker, tmp_path: Path
) -> None:
    cookies = cookie_file(tmp_path)
    argv = ["get", URL, "--plugin", "alpha", "-o", str(tmp_path / "o"), "--cookies", str(cookies)]
    loud = runner.invoke(app, argv)
    assert loud.exit_code == 0, loud.stderr
    assert "cookies were not passed to plugin alpha" in loud.stderr and "cookies permission" in loud.stderr
    quiet = runner.invoke(app, [*argv, "--quiet"])
    assert "not passed" not in quiet.stderr


def test_a_plugin_with_the_cookies_permission_gets_no_such_notice(docker: FakeDocker, tmp_path: Path) -> None:
    cookies = cookie_file(tmp_path)
    result = runner.invoke(app, ["get", URL, "-o", str(tmp_path / "o"), "--cookies", str(cookies)])
    assert result.exit_code == 0, result.stderr
    assert "not passed" not in result.stderr


def test_a_link_claimed_only_by_a_plugin_without_download_is_not_given_to_it(
    fake_docker: FakeDocker, serve, tmp_path: Path
) -> None:
    install(Plugin("lookup", A_IMAGE, "1", frozenset({"check", "info"}), frozenset()))
    fake_docker.set(plugins={A_IMAGE: {"check": True}})
    srv = serve(b"abc")
    result = runner.invoke(app, ["get", srv.url(), "-o", str(tmp_path / "o"), "--quiet"])
    assert result.exit_code == 0, result.stderr
    assert (tmp_path / "o" / "file.bin").read_bytes() == b"abc"
    assert downloads(fake_docker) == []


def test_a_bad_cookies_file_does_not_stop_a_plugin_without_the_permission(docker: FakeDocker, tmp_path: Path) -> None:
    result = get(URL, "--plugin", "alpha", "--cookies", str(tmp_path / "missing.txt"), out=tmp_path / "o")
    assert result.exit_code == 0, result.stderr


def test_the_mode_warning_is_only_for_a_route_that_reads_the_cookies(docker: FakeDocker, tmp_path: Path) -> None:
    cookies = cookie_file(tmp_path, mode=0o644)
    quiet = runner.invoke(app, ["get", URL, "--plugin", "alpha", "-o", str(tmp_path / "o"), "--cookies", str(cookies)])
    assert "can be read by other users" not in quiet.stderr
    loud = runner.invoke(app, ["get", URL, "-o", str(tmp_path / "o"), "--cookies", str(cookies)])
    assert "can be read by other users" in loud.stderr


def test_a_bad_cookies_file_stops_a_plugin_with_the_permission(docker: FakeDocker, tmp_path: Path) -> None:
    result = get(URL, "--plugin", "beta", "--cookies", str(tmp_path / "missing.txt"), out=tmp_path / "o")
    assert result.exit_code == 2 and "missing.txt" in result.stderr
    assert downloads(docker) == []


def test_a_path_outside_the_folder_from_a_plugin_is_refused(
    docker: FakeDocker, tmp_path: Path
) -> None:
    docker.set(plugins={
        A_IMAGE: {"check": False},
        B_IMAGE: {"check": True, "lines": [HELLO, {"type": "done", "paths": ["/etc/passwd"]}]},
    })
    result = get(URL, out=tmp_path / "o")
    assert result.exit_code == 1 and "outside the output folder" in result.stderr
