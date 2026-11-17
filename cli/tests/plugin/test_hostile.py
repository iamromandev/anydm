"""A plugin is untrusted: whatever it prints, anydm must neither crash nor show or touch what it should not.

Each test drives a fake docker that prints hostile output through the public commands."""

import json
import os
from pathlib import Path

import pytest
from src import engine
from src.main import app
from src.plugin import route, state
from src.plugin.model import Plugin
from src.plugin.runner import PluginRunner
from typer.testing import CliRunner, Result

from tests.conftest import FakeDocker

runner = CliRunner()
HELLO = {"type": "hello", "protocol": 3}
IMAGE = "ghcr.io/acme/evil@sha256:" + "c" * 64
URL = "https://example.org/thing/1"
ALL = frozenset({"check", "download", "info"})
EVIL = "x‮evil\x1b[31m\x07red⁦"  # bidi overrides, an ANSI escape and a bell
FORBIDDEN = ("‮", "⁦", "\x1b", "\x07")


@pytest.fixture(autouse=True)
def _env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)
    state.save([Plugin("evil", IMAGE, "1", ALL, frozenset({"network"}))])


def hostile(fake: FakeDocker, **keys: object) -> None:
    fake.set(plugins={IMAGE: {"check": True, **keys}})


def get(tmp_path: Path, *extra: str) -> Result:
    return runner.invoke(app, ["get", URL, "--plugin", "evil", "-o", str(tmp_path / "out"), "--quiet", *extra])


def test_a_huge_line_and_bad_json_are_skipped_and_the_download_still_completes(
    fake_docker: FakeDocker, tmp_path: Path
) -> None:
    huge = '{"type":"notice","message":"' + "A" * 2_000_000 + '"}'
    hostile(fake_docker, lines=[HELLO, huge, "{not json", "", "[1, 2]", '"text"', '{"type":"mystery"}',
                                {"type": "done", "paths": ["/downloads/a.bin"]}])
    result = get(tmp_path)
    assert result.exit_code == 0, result.output
    assert result.stdout.strip() == str((tmp_path / "out").resolve() / "a.bin")


def test_a_flood_of_lines_ends_the_download_with_an_error_not_a_hang(fake_docker: FakeDocker, tmp_path: Path) -> None:
    hostile(fake_docker, lines=[HELLO, *(["junk"] * 10_050), {"type": "done", "paths": ["/downloads/a.bin"]}])
    result = get(tmp_path)
    assert result.exit_code == 1
    assert "too many lines" in result.output


@pytest.mark.parametrize("path", [
    "/etc/passwd", "/downloads/../escape.bin", "/downloads/a/../../escape.bin", "../escape.bin", "/downloads", "",
    "/downloads/\x00x",
])
def test_a_reported_path_outside_the_folder_fails_and_nothing_is_written_there(
    fake_docker: FakeDocker, tmp_path: Path, path: str
) -> None:
    hostile(fake_docker, lines=[HELLO, {"type": "done", "paths": [path]}])
    result = get(tmp_path)
    assert result.exit_code == 1
    assert "outside the output folder" in result.output or "not a usable path" in result.output
    assert not (tmp_path / "escape.bin").exists()


def test_a_partial_path_outside_the_folder_fails_too(fake_docker: FakeDocker, tmp_path: Path) -> None:
    hostile(fake_docker, lines=[HELLO, {"type": "error", "message": "boom", "partial": ["/downloads/../x"]}], exit=1)
    result = get(tmp_path)
    assert result.exit_code == 1
    assert "outside the output folder" in result.output


def test_control_and_bidi_characters_never_reach_the_terminal(fake_docker: FakeDocker, tmp_path: Path) -> None:
    hostile(fake_docker, lines=[HELLO, {"type": "notice", "message": EVIL},
                                {"type": "error", "message": EVIL, "partial": []}], exit=1)
    result = runner.invoke(app, ["get", URL, "--plugin", "evil", "-o", str(tmp_path / "out")])
    assert result.exit_code == 1
    assert "evil" in result.output
    assert not any(c in result.output for c in FORBIDDEN)


@pytest.mark.parametrize("fields", [
    {"done": float("nan")}, {"done": 1e999}, {"done": 5, "total": 1e999}, {"done": 5, "total": float("nan")},
    {"done": -5, "total": 10}, {"done": "5"}, {"done": 5, "speed": 1e999}, {"done": 5, "name": EVIL},
])
def test_hostile_progress_values_never_stop_the_download(
    fake_docker: FakeDocker, tmp_path: Path, fields: dict[str, float | int | str]
) -> None:
    line = json.dumps({"type": "progress", **fields})  # NaN and Infinity are written as-is
    hostile(fake_docker, lines=[HELLO, line, {"type": "done", "paths": ["/downloads/a.bin"]}])
    result = get(tmp_path)
    assert result.exit_code == 0, result.output
    assert not any(c in result.output for c in FORBIDDEN)


def test_a_hostile_info_is_cleaned_and_capped(fake_docker: FakeDocker) -> None:
    formats = [{"id": f"f{i}", "ext": EVIL, "note": "n" * 1000} for i in range(600)]
    hostile(fake_docker, info_lines=[HELLO, {"type": "info", "title": EVIL * 300, "size": "big", "formats": formats,
                                             "items": "not a list"}])
    result = runner.invoke(app, ["info", URL, "--plugin", "evil", "--json"])
    assert result.exit_code == 0, result.output
    shown = json.loads(result.stdout)
    assert len(shown["formats"]) == 500
    assert len(shown["title"]) <= 500
    assert shown["size"] is None
    assert all(len(f["note"]) == 500 for f in shown["formats"])
    assert shown["items"] == []
    assert not any(c in result.stdout for c in FORBIDDEN)


@pytest.mark.parametrize("lines", [
    [HELLO, "{broken", {"type": "supported", "value": "yes"}],
    [HELLO, {"type": "supported"}],
    [{"type": "supported", "value": True}],
    ["A" * 2_000_000],
    [HELLO, {"type": "error", "message": EVIL}],
])
def test_a_check_with_hostile_output_is_skipped_with_a_clean_notice(
    fake_docker: FakeDocker, lines: list[object]
) -> None:
    hostile(fake_docker, check_lines=lines)
    notes: list[str] = []
    picked = route.pick(URL, state.load(), PluginRunner(os.environ["ANYDM_DOCKER"], "anydm-media:0.0.1"), notes.append)
    assert picked is None
    assert any("skipped plugin evil" in n for n in notes)
    assert not any(c in n for n in notes for c in FORBIDDEN)


@pytest.mark.parametrize("manifest", [
    {"name": "evil‮", "version": "1", "capabilities": ["download"], "permissions": []},
    {"name": "../etc", "version": "1", "capabilities": ["download"], "permissions": []},
    {"name": "ok-name", "version": "1", "capabilities": ["download", "root"], "permissions": []},
    {"name": "ok-name", "version": "1", "capabilities": ["download"], "permissions": ["network", "docker-socket"]},
    {"name": "media", "version": "1", "capabilities": ["download"], "permissions": []},
    {"name": "ok-name", "version": "1", "capabilities": "download", "permissions": []},
])
def test_a_hostile_manifest_is_refused_and_nothing_is_saved(fake_docker: FakeDocker, manifest: dict[str, object]) -> None:
    before = state.state_path().read_text()
    ref = "ghcr.io/acme/new@sha256:" + "d" * 64
    fake_docker.set(plugins={ref: {"manifest": manifest}})
    result = runner.invoke(app, ["plugin", "add", ref, "--yes"])
    assert result.exit_code == 2, result.output
    assert not any(c in result.output for c in FORBIDDEN)
    assert state.state_path().read_text() == before
