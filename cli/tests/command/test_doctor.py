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
