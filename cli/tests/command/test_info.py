import json
from pathlib import Path
from typing import Any

import pytest
import yt_dlp
from src import engine
from src.main import app
from typer.testing import CliRunner

from tests.engine.test_media import FakeYDL

runner = CliRunner()
YOUTUBE = "https://www.youtube.com/watch?v=abc"
HELLO = {"type": "hello", "protocol": 3}
COOKIES_TEXT = "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tFALSE\t2000000000\tsid\tsecretvalue\n"


@pytest.fixture(autouse=True)
def _env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    monkeypatch.delenv("ANYDM_COOKIES_FILE", raising=False)


@pytest.fixture
def container_media(monkeypatch: pytest.MonkeyPatch, fake_docker):
    """yt-dlp is not installed on the host: media goes through the fake docker."""
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)
    fake_docker.set(check=True)
    return fake_docker


def info_call(fake) -> list[str]:
    [call] = [c for c in fake.calls() if c[0] == "run" and "info" in c[c.index("--") + 1:]]
    return call


def mounts(call: list[str]) -> list[str]:
    return [call[i + 1] for i, a in enumerate(call) if a == "--mount"]


INFO_LINE = {"type": "info", "title": "A video", "size": 2048,
             "formats": [{"id": "18", "ext": "mp4", "note": "360p"}],
             "items": [{"title": "part one", "url": "https://x/1"}]}


def test_media_info_through_a_container_is_readable(container_media) -> None:
    container_media.set(info_lines=[HELLO, INFO_LINE])
    result = runner.invoke(app, ["info", YOUTUBE])
    assert result.exit_code == 0, result.stderr
    out = result.stdout
    assert "A video" in out and "2.0 KiB" in out
    assert "18" in out and "mp4" in out and "360p" in out
    assert "part one" in out and "https://x/1" in out
    call = info_call(container_media)
    assert call[call.index("--") + 1:] == [call[call.index("--") + 1], "info", "--protocol", "3", f"--url={YOUTUBE}"]
    assert mounts(call) == [] and "-v" not in call


def test_media_info_json(container_media) -> None:
    container_media.set(info_lines=[HELLO, INFO_LINE])
    result = runner.invoke(app, ["info", YOUTUBE, "--json"])
    assert result.exit_code == 0, result.stderr
    assert json.loads(result.stdout) == {
        "kind": "media", "plugin": "media", "title": "A video", "size": 2048,
        "formats": [{"id": "18", "ext": "mp4", "note": "360p"}],
        "items": [{"title": "part one", "url": "https://x/1"}],
    }


def test_media_info_with_cookies_mounts_them_for_info_only(container_media, tmp_path: Path) -> None:
    cookies = tmp_path / "c.txt"
    cookies.write_text(COOKIES_TEXT)
    cookies.chmod(0o600)
    container_media.set(info_lines=[HELLO, INFO_LINE])
    result = runner.invoke(app, ["info", YOUTUBE, "--cookies", str(cookies)])
    assert result.exit_code == 0, result.stderr
    call = info_call(container_media)
    assert mounts(call) == [f"type=bind,src={cookies.resolve()},dst=/run/anydm/cookies.txt,readonly"]
    assert call[-1] == "--cookies"
    checks = [c for c in container_media.calls() if c[0] == "run" and "check" in c[c.index("--") + 1:]]
    assert checks and all("--mount" not in c for c in checks)  # never for the classification


def test_a_bad_cookies_file_stops_a_media_info(container_media, tmp_path: Path) -> None:
    bad = tmp_path / "bad.txt"
    bad.write_text("not cookies\n")
    result = runner.invoke(app, ["info", YOUTUBE, "--cookies", str(bad)])
    assert result.exit_code == 2
    assert "Netscape" in result.stderr
    assert not [c for c in container_media.calls() if c[0] == "run" and "info" in c[c.index("--") + 1:]]


def test_an_error_line_exits_1(container_media) -> None:
    container_media.set(info_lines=[HELLO, {"type": "error", "message": "Unsupported URL", "partial": []}], info_exit=1)
    result = runner.invoke(app, ["info", YOUTUBE])
    assert result.exit_code == 1
    assert "Unsupported URL" in result.stderr and result.stdout == ""


def test_without_docker_a_media_link_is_looked_up_as_http_with_a_notice(
    monkeypatch: pytest.MonkeyPatch, fake_docker, serve
) -> None:
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)
    fake_docker.set(version="fail")
    srv = serve(b"abc")
    result = runner.invoke(app, ["info", srv.url()])
    assert result.exit_code == 0, result.stderr
    assert "media detection skipped" in result.stderr


def test_media_info_in_process(monkeypatch: pytest.MonkeyPatch, fake_docker) -> None:
    seen: list[dict[str, Any]] = []

    class InfoOnly(FakeYDL):
        def __init__(self, opts: dict[str, Any]) -> None:
            seen.append(opts)

        def extract_info(self, url: str, download: bool = True) -> dict[str, Any]:
            assert download is False
            return {"title": "Local", "filesize": 10}

    monkeypatch.setattr(yt_dlp, "YoutubeDL", InfoOnly)
    monkeypatch.setattr(engine, "_media_supported", lambda url: True)
    result = runner.invoke(app, ["info", YOUTUBE, "--json"])
    assert result.exit_code == 0, result.stderr
    assert json.loads(result.stdout)["title"] == "Local"
    assert fake_docker.calls() == []


def test_http_info_uses_head_and_prints_the_name_and_size(serve) -> None:
    srv = serve(b"x" * 3000, disposition='attachment; filename="report.pdf"')
    result = runner.invoke(app, ["info", srv.url()])
    assert result.exit_code == 0, result.stderr
    assert "report.pdf" in result.stdout and "2.9 KiB" in result.stdout
    assert [r.method for r in srv.log] == ["HEAD"]


def test_http_info_name_from_the_url_and_redirects_followed(serve) -> None:
    srv = serve(b"x" * 10, redirects={"/old": "/file.bin"})
    result = runner.invoke(app, ["info", srv.url("/old"), "--json"])
    assert result.exit_code == 0, result.stderr
    assert json.loads(result.stdout) == {"kind": "http", "plugin": None, "title": "file.bin", "size": 10,
                                         "formats": [], "items": []}


def test_http_info_without_a_length_has_no_size(serve) -> None:
    srv = serve(b"x" * 10, length=False)
    out = json.loads(runner.invoke(app, ["info", srv.url(), "--json"]).stdout)
    assert out["size"] is None


def test_http_info_404_exits_1(serve) -> None:
    srv = serve(b"x", missing={"/nope"})
    result = runner.invoke(app, ["info", srv.url("/nope")])
    assert result.exit_code == 1
    assert "404" in result.stderr


def test_http_info_never_sends_cookies(serve, tmp_path: Path) -> None:
    cookies = tmp_path / "c.txt"
    cookies.write_text(COOKIES_TEXT)
    cookies.chmod(0o600)
    srv = serve(b"x")
    result = runner.invoke(app, ["info", srv.url(), "--cookies", str(cookies)])
    assert result.exit_code == 0, result.stderr
    assert all(r.cookie is None for r in srv.log)
    assert "cookies apply to media only" in result.stderr


def test_http_info_text_from_the_server_is_cleaned(serve) -> None:
    srv = serve(b"x")
    result = runner.invoke(app, ["info", srv.url("/a%1B%E2%80%AEb.bin")])
    assert result.exit_code == 0, result.stderr
    assert "\x1b" not in result.stdout and "\u202e" not in result.stdout and "ab.bin" in result.stdout


@pytest.mark.parametrize("source", ["magnet:?xt=urn:btih:abc", "https://example.com/a.torrent"])
def test_a_torrent_has_no_info(source: str) -> None:
    result = runner.invoke(app, ["info", source])
    assert result.exit_code == 2
    assert "torrent" in result.stderr


def test_an_unusable_link_exits_2() -> None:
    result = runner.invoke(app, ["info", "ftp://x/y"])
    assert result.exit_code == 2
