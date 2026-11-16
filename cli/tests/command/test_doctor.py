import pytest
from src.main import app
from typer.testing import CliRunner

runner = CliRunner()


def test_doctor_reports_missing_ffmpeg_and_exits_zero(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    monkeypatch.setenv("PATH", str(tmp_path))
    result = runner.invoke(app, ["doctor"])
    assert result.exit_code == 0
    assert "ffmpeg: missing" in result.stdout
    assert "deno: missing" in result.stdout


def test_doctor_reports_found_tool(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    tool = tmp_path / "ffmpeg"
    tool.write_text("#!/bin/sh\n")
    tool.chmod(0o755)
    monkeypatch.setenv("PATH", str(tmp_path))
    result = runner.invoke(app, ["doctor"])
    assert "ffmpeg: ok" in result.stdout


def test_version_prints_package_version() -> None:
    result = runner.invoke(app, ["--version"])
    assert result.exit_code == 0
    assert result.stdout.strip() == "0.0.1"


def test_doctor_reports_the_built_in_torrent_client() -> None:
    result = runner.invoke(app, ["doctor"])
    assert result.exit_code == 0
    assert "torrent: built-in (pure Python client, .torrent and magnet)" in result.stdout


@pytest.fixture
def no_local_yt_dlp(monkeypatch: pytest.MonkeyPatch) -> None:
    from src import engine

    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)


def test_doctor_reports_docker_and_the_image(no_local_yt_dlp: None, fake_docker) -> None:
    result = runner.invoke(app, ["doctor"])
    assert result.exit_code == 0
    assert "docker: ok" in result.stdout
    assert "media image: ok (anydm-media:0.0.1)" in result.stdout
    assert "pull" not in fake_docker.commands() and "run" not in fake_docker.commands()


def test_doctor_reports_a_missing_image(no_local_yt_dlp: None, fake_docker) -> None:
    fake_docker.set(image=False)
    result = runner.invoke(app, ["doctor"])
    assert result.exit_code == 0
    assert "docker: ok" in result.stdout
    assert "media image: missing" in result.stdout
    assert "pull" not in fake_docker.commands()


def test_doctor_reports_missing_docker_and_exits_zero(no_local_yt_dlp: None, fake_docker) -> None:
    fake_docker.set(version="fail")
    result = runner.invoke(app, ["doctor"])
    assert result.exit_code == 0
    assert "docker: missing" in result.stdout
    assert "media image: missing" in result.stdout
    assert fake_docker.commands() == ["version"]


def test_doctor_without_local_yt_dlp_drops_the_host_tool_lines(no_local_yt_dlp: None, fake_docker) -> None:
    result = runner.invoke(app, ["doctor"])
    for gone in ("ffmpeg", "deno", "yt_dlp"):
        assert gone not in result.stdout.replace("media image", "")


def test_doctor_with_local_yt_dlp_says_media_runs_in_process(fake_docker) -> None:
    result = runner.invoke(app, ["doctor"])
    assert "yt_dlp: ok (media runs in-process)" in result.stdout
    assert "ffmpeg:" in result.stdout and "deno:" in result.stdout
    assert fake_docker.calls() == []


def test_doctor_with_an_invalid_media_image_is_a_usage_error(
    no_local_yt_dlp: None, fake_docker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("ANYDM_MEDIA_IMAGE", "-v/x:/y")
    result = runner.invoke(app, ["doctor"])
    assert result.exit_code == 2
    assert "media_image" in result.stderr
    assert fake_docker.calls() == []


SECRET = "s3cr3t-session-value-7f3a"


@pytest.fixture
def cookies_env(tmp_path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    monkeypatch.delenv("ANYDM_COOKIES_FILE", raising=False)


def cookie_file(tmp_path, text: str | None = None, mode: int = 0o600):
    f = tmp_path / "cookies.txt"
    f.write_text(text or f"# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tFALSE\t2000000000\tsid\t{SECRET}\n")
    f.chmod(mode)
    return f


def test_doctor_says_when_no_cookies_are_set(cookies_env: None) -> None:
    result = runner.invoke(app, ["doctor"])
    assert result.exit_code == 0
    assert "cookies: not set" in result.stdout


def test_doctor_reports_a_valid_cookies_file_by_path_and_never_its_content(
    cookies_env: None, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    f = cookie_file(tmp_path)
    monkeypatch.setenv("ANYDM_COOKIES_FILE", str(f))
    result = runner.invoke(app, ["doctor"])
    assert result.exit_code == 0
    assert f"cookies: ok ({f.resolve()})" in result.stdout.replace("\n", "")
    assert SECRET not in result.stdout + result.stderr
    assert "warning" not in result.stdout


def test_doctor_warns_about_a_cookies_file_others_can_read(
    cookies_env: None, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("ANYDM_COOKIES_FILE", str(cookie_file(tmp_path, mode=0o644)))
    result = runner.invoke(app, ["doctor"])
    assert result.exit_code == 0
    assert "cookies: ok" in result.stdout
    assert "chmod 600" in " ".join(result.stdout.split())


@pytest.mark.parametrize("what", ["missing", "format"])
def test_doctor_reports_an_invalid_cookies_file_without_content_and_exits_zero(
    cookies_env: None, tmp_path, monkeypatch: pytest.MonkeyPatch, what: str
) -> None:
    f = tmp_path / "gone.txt" if what == "missing" else cookie_file(tmp_path, text=f"{SECRET} nope\n")
    monkeypatch.setenv("ANYDM_COOKIES_FILE", str(f))
    result = runner.invoke(app, ["doctor"])
    assert result.exit_code == 0
    assert "cookies: invalid (" in result.stdout
    assert f.name in result.stdout.replace("\n", "")
    assert SECRET not in result.stdout + result.stderr


def test_doctor_reads_the_cookies_setting_from_config_toml(cookies_env: None, tmp_path) -> None:
    f = cookie_file(tmp_path)
    folder = tmp_path / "xdg" / "anydm"
    folder.mkdir(parents=True)
    (folder / "config.toml").write_text(f'cookies_file = "{f}"\n')
    result = runner.invoke(app, ["doctor"])
    assert "cookies: ok" in result.stdout
