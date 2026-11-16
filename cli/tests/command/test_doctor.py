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
