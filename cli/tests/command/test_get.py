import os
import pty
import select
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest
from src import engine
from src.engine.http import resume
from src.main import app
from typer.testing import CliRunner

runner = CliRunner()
CLI_DIR = Path(__file__).parents[2]


@pytest.fixture(autouse=True)
def _env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    monkeypatch.delenv("ANYDM_CONNECTIONS", raising=False)
    monkeypatch.delenv("ANYDM_DOWNLOAD_DIR", raising=False)
    monkeypatch.delenv("ANYDM_COOKIES_FILE", raising=False)


def test_quiet_success_prints_only_absolute_paths_on_stdout(serve, tmp_path: Path) -> None:
    data = os.urandom(1000)
    srv = serve(data)
    out = tmp_path / "out"
    result = runner.invoke(app, ["get", srv.url(), "-o", str(out), "--quiet"])
    assert result.exit_code == 0
    assert result.stdout == f"{(out / 'file.bin').resolve()}\n"
    assert result.stderr == ""
    assert (out / "file.bin").read_bytes() == data


def test_uses_download_dir_from_env(serve, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    srv = serve(b"abc")
    monkeypatch.setenv("ANYDM_DOWNLOAD_DIR", str(tmp_path / "envdir"))
    result = runner.invoke(app, ["get", srv.url(), "--quiet"])
    assert result.exit_code == 0
    assert (tmp_path / "envdir" / "file.bin").read_bytes() == b"abc"


def test_404_exits_1_with_status_on_stderr(serve) -> None:
    srv = serve(b"x", missing={"/nope"})
    result = runner.invoke(app, ["get", srv.url("/nope"), "--quiet"])
    assert result.exit_code == 1
    assert "404" in result.stderr
    assert result.stdout == ""


def test_too_many_connections_exits_2_naming_the_setting(serve) -> None:
    srv = serve(b"x")
    result = runner.invoke(app, ["get", srv.url(), "-c", "99"])
    assert result.exit_code == 2
    assert "connections" in result.stderr
    assert srv.log == []


def test_unsupported_source_exits_2() -> None:
    result = runner.invoke(app, ["get", "ftp://x/y"])
    assert result.exit_code == 2
    assert result.stderr.startswith("error:")


def test_unwritable_output_exits_2_before_any_request(serve, tmp_path: Path) -> None:
    srv = serve(b"x")
    blocker = tmp_path / "file"
    blocker.write_text("not a folder")
    result = runner.invoke(app, ["get", srv.url(), "-o", str(blocker / "sub"), "--quiet"])
    assert result.exit_code == 2
    assert srv.log == []


def test_a_magnet_without_a_usable_infohash_exits_2() -> None:
    result = runner.invoke(app, ["get", "magnet:?dn=nothing", "--quiet"])
    assert result.exit_code == 2
    assert "infohash" in result.stderr


def test_keyboard_interrupt_exits_130(serve, monkeypatch: pytest.MonkeyPatch) -> None:
    class Interrupting:
        async def run(self, job, report):
            raise KeyboardInterrupt

    monkeypatch.setattr(engine, "engine_for", lambda kind: Interrupting())
    srv = serve(b"x")
    result = runner.invoke(app, ["get", srv.url(), "--quiet"])
    assert result.exit_code == 130


def test_progress_goes_to_stderr_on_a_tty(serve, tmp_path: Path) -> None:
    srv = serve(os.urandom(4 << 20), delay=0.05)
    out = tmp_path / "o"
    master, slave = pty.openpty()
    proc = subprocess.Popen(
        [sys.executable, "-c", "from src.main import main; main()", "get", srv.url(), "-o", str(out)],
        cwd=CLI_DIR,
        env={**os.environ, "HOME": str(tmp_path / "home")},
        stdout=subprocess.PIPE,
        stderr=slave,
        text=True,
    )
    os.close(slave)
    chunks = []
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:  # read while it runs: a closed pty can drop unread output
        if select.select([master], [], [], 0.1)[0]:
            try:
                chunk = os.read(master, 65536)
            except OSError:
                break
            if not chunk:
                break
            chunks.append(chunk)
        elif proc.poll() is not None:
            break
    stdout = proc.communicate(timeout=10)[0]
    os.close(master)
    stderr = b"".join(chunks).decode(errors="replace")
    assert proc.returncode == 0, stderr
    assert stdout.strip() == str((out / "file.bin").resolve())
    assert "100%" in stderr
    assert "/s" in stderr  # speed, shown while the download runs
    assert "0:00:00" in stderr  # the ETA column


def test_sigint_exits_130_and_keeps_partial_state(serve, tmp_path: Path) -> None:
    srv = serve(os.urandom(8 << 20), delay=0.05)
    out = tmp_path / "out"
    proc = subprocess.Popen(
        [sys.executable, "-c", "from src.main import main; main()", "get", srv.url(), "-o", str(out), "--quiet"],
        cwd=CLI_DIR,
        env={**os.environ, "HOME": str(tmp_path / "home")},
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    deadline = time.monotonic() + 20
    while srv.sent < 1 << 20 and time.monotonic() < deadline:
        time.sleep(0.05)
    proc.send_signal(signal.SIGINT)
    stdout, stderr = proc.communicate(timeout=20)
    assert proc.returncode == 130, stderr
    assert stdout == ""
    assert not (out / "file.bin").exists()
    assert (out / "file.bin.part.json").exists()


def test_non_tty_prints_notices_but_not_progress(serve, tmp_path: Path) -> None:
    srv = serve(os.urandom(1000))
    out = tmp_path / "out"
    out.mkdir()
    final = out / "file.bin"
    resume.part_path(final).write_bytes(b"x" * 1000)
    resume.write(final, resume.Record(srv.url(), srv.url(), 1000, srv.etag, srv.last_modified, []))
    result = runner.invoke(app, ["get", srv.url(), "-o", str(out)])
    assert result.exit_code == 0
    assert "cannot resume a single-stream download; starting over" in result.stderr
    assert "%" not in result.stderr


def test_rejected_source_leaves_no_output_folder(tmp_path: Path) -> None:
    out = tmp_path / "newdir"
    result = runner.invoke(app, ["get", "ftp://x/y", "-o", str(out)])
    assert result.exit_code == 2
    assert not out.exists()


def test_second_interrupt_during_cleanup_still_exits_130(serve, monkeypatch: pytest.MonkeyPatch) -> None:
    class Interrupting:
        async def run(self, job, report):
            raise KeyboardInterrupt

    def boom(self) -> None:
        raise KeyboardInterrupt

    monkeypatch.setattr(engine, "engine_for", lambda kind: Interrupting())
    monkeypatch.setattr("src.core.progress.NoticeReporter.close", boom)
    result = runner.invoke(app, ["get", serve(b"x").url()])
    assert result.exit_code == 130


YOUTUBE = "https://www.youtube.com/watch?v=abc"
HELLO = {"type": "hello", "protocol": 2}


@pytest.fixture
def container_media(monkeypatch: pytest.MonkeyPatch, fake_docker) -> object:
    """yt-dlp is not installed on the host: media goes through the fake docker."""
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)
    fake_docker.set(check=True)
    return fake_docker


def test_media_through_a_container_prints_the_host_paths(container_media, tmp_path: Path) -> None:
    container_media.set(lines=[
        HELLO,
        {"type": "progress", "done": 1, "total": 2, "speed": 1.0, "name": "v.part"},
        {"type": "done", "paths": ["/downloads/v [abc].mp4"]},
    ])
    out = tmp_path / "out"
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(out), "--quiet"])
    assert result.exit_code == 0, result.stderr
    assert result.stdout == f"{(out / 'v [abc].mp4').resolve()}\n"
    [call] = [c for c in container_media.calls() if c[0] == "run" and "download" in c]
    assert "--rm" in call and "--user" in call and call.count("-v") == 1
    assert call[call.index("-v") + 1] == f"{out.resolve()}:/downloads"


def test_an_error_line_from_the_container_exits_1(container_media, tmp_path: Path) -> None:
    container_media.set(lines=[HELLO, {"type": "error", "message": "no such video", "partial": []}], exit=1)
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o"), "--quiet"])
    assert result.exit_code == 1
    assert "no such video" in result.stderr
    assert result.stdout == ""


def test_a_path_outside_the_folder_from_the_container_exits_1(container_media, tmp_path: Path) -> None:
    container_media.set(lines=[HELLO, {"type": "done", "paths": ["/etc/passwd"]}])
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o"), "--quiet"])
    assert result.exit_code == 1
    assert "outside the output folder" in result.stderr
    assert result.stdout == ""


def test_without_docker_a_plain_url_downloads_over_http_with_a_notice(
    monkeypatch: pytest.MonkeyPatch, fake_docker, serve, tmp_path: Path
) -> None:
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)
    fake_docker.set(version="fail")
    srv = serve(b"abc")
    result = runner.invoke(app, ["get", srv.url(), "-o", str(tmp_path / "o")])
    assert result.exit_code == 0
    assert (tmp_path / "o" / "file.bin").read_bytes() == b"abc"
    assert "media detection skipped" in result.stderr and "Docker" in result.stderr


def test_forced_media_without_docker_exits_1_naming_docker(
    monkeypatch: pytest.MonkeyPatch, fake_docker, tmp_path: Path
) -> None:
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)
    fake_docker.set(version="fail")
    result = runner.invoke(app, ["get", YOUTUBE, "--kind", "media", "-o", str(tmp_path / "o")])
    assert result.exit_code == 1
    assert "media downloads need Docker; see anydm doctor" in result.stderr


def test_with_yt_dlp_installed_no_docker_command_runs(fake_docker, serve, tmp_path: Path) -> None:
    srv = serve(b"abc")
    result = runner.invoke(app, ["get", srv.url(), "-o", str(tmp_path / "o"), "--quiet"])
    assert result.exit_code == 0
    assert fake_docker.calls() == []


def test_an_invalid_media_image_is_a_usage_error_and_runs_no_docker(
    monkeypatch: pytest.MonkeyPatch, fake_docker, tmp_path: Path
) -> None:
    monkeypatch.setenv("ANYDM_MEDIA_IMAGE", "-v/x:/y")
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o")])
    assert result.exit_code == 2
    assert "media_image" in result.stderr
    assert fake_docker.calls() == []


def test_a_protocol_mismatch_during_classification_exits_1_asking_for_an_update(
    container_media, tmp_path: Path
) -> None:
    container_media.set(check_lines=[{"type": "hello", "protocol": 99}])
    result = runner.invoke(app, ["get", "https://example.com/a.bin", "-o", str(tmp_path / "o")])
    assert result.exit_code == 1
    assert "update the media image" in result.stderr
    assert not (tmp_path / "o" / "a.bin").exists()


def test_a_dotenv_in_the_current_folder_cannot_choose_the_docker_command(
    monkeypatch: pytest.MonkeyPatch, fake_docker, tmp_path: Path
) -> None:
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)
    evil = tmp_path / "evil"
    marker = tmp_path / "ran"
    evil.write_text(f"#!/bin/sh\ntouch {marker}\n")
    evil.chmod(0o755)
    monkeypatch.delenv("ANYDM_DOCKER")
    cwd = tmp_path / "cwd"
    cwd.mkdir()
    (cwd / ".env").write_text(f"ANYDM_DOCKER={evil}\n")
    monkeypatch.chdir(cwd)
    monkeypatch.setenv("PATH", str(tmp_path / "empty"))
    runner.invoke(app, ["get", YOUTUBE, "--kind", "media", "-o", str(tmp_path / "o")])
    assert not marker.exists()


SECRET = "s3cr3t-session-value-7f3a"
TARGET = "/run/anydm/cookies.txt"


def cookie_file(folder: Path, name: str = "cookies.txt", mode: int = 0o600) -> Path:
    f = folder / name
    f.write_text(f"# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tFALSE\t2000000000\tsid\t{SECRET}\n")
    f.chmod(mode)
    return f


def download_call(docker) -> list[str]:
    [call] = [c for c in docker.calls() if c[0] == "run" and "download" in c]
    return call


def cookie_mounts(call: list[str]) -> list[str]:
    return [call[i + 1] for i, a in enumerate(call) if a == "--mount"]


def test_cookies_flag_mounts_the_file_for_the_media_container_and_not_for_the_check(
    container_media, tmp_path: Path
) -> None:
    container_media.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/v.mp4"]}])
    cookies = cookie_file(tmp_path)
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o"), "--quiet", "--cookies", str(cookies)])
    assert result.exit_code == 0, result.stderr
    call = download_call(container_media)
    assert cookie_mounts(call) == [f"type=bind,src={cookies.resolve()},dst={TARGET},readonly"]
    assert call[-1] == "--cookies"
    [check] = [c for c in container_media.calls() if c[0] == "run" and "check" in c]
    assert "--mount" not in check and "--cookies" not in check


def test_cookies_setting_from_env_is_used_and_the_flag_beats_it(
    container_media, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    container_media.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/v.mp4"]}])
    from_env = cookie_file(tmp_path, "env.txt")
    from_flag = cookie_file(tmp_path, "flag.txt")
    monkeypatch.setenv("ANYDM_COOKIES_FILE", str(from_env))
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o"), "--quiet"])
    assert result.exit_code == 0, result.stderr
    assert f"src={from_env.resolve()}," in cookie_mounts(download_call(container_media))[0]
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o"), "--quiet", "--cookies", str(from_flag)])
    assert result.exit_code == 0, result.stderr
    last = [c for c in container_media.calls() if c[0] == "run" and "download" in c][-1]
    assert f"src={from_flag.resolve()}," in cookie_mounts(last)[0]


def test_cookies_setting_from_config_toml_is_used(container_media, tmp_path: Path) -> None:
    container_media.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/v.mp4"]}])
    cookies = cookie_file(tmp_path)
    folder = tmp_path / "xdg" / "anydm"
    folder.mkdir(parents=True)
    (folder / "config.toml").write_text(f'cookies_file = "{cookies}"\n')
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o"), "--quiet"])
    assert result.exit_code == 0, result.stderr
    assert f"src={cookies.resolve()}," in cookie_mounts(download_call(container_media))[0]


def test_cookies_file_in_a_dotenv_is_ignored(container_media, tmp_path: Path) -> None:
    container_media.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/v.mp4"]}])
    cookies = cookie_file(tmp_path)
    (tmp_path / ".env").write_text(f"ANYDM_COOKIES_FILE={cookies}\n")
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o"), "--quiet"])
    assert result.exit_code == 0, result.stderr
    assert cookie_mounts(download_call(container_media)) == []


def _bad_files(tmp_path: Path) -> dict[str, Path]:
    not_netscape = tmp_path / "json.txt"
    not_netscape.write_text(f'{{"sid": "{SECRET}"}}\n')
    big = tmp_path / "big.txt"
    big.write_text("# Netscape HTTP Cookie File\n")
    os.truncate(big, 10 * 1024 * 1024 + 1)
    one_bad_line = tmp_path / "badline.txt"  # what yt-dlp would print, raw, on its stderr
    one_bad_line.write_text(cookie_file(tmp_path).read_text() + f"oops {SECRET}\n")
    return {
        "missing": tmp_path / "missing.txt", "directory": tmp_path, "format": not_netscape, "big": big,
        "badline": one_bad_line,
    }


@pytest.mark.parametrize("which", ["missing", "directory", "format", "big", "badline"])
def test_a_bad_cookies_file_exits_2_naming_the_path_before_any_media_download(
    container_media, tmp_path: Path, which: str
) -> None:
    bad = _bad_files(tmp_path)[which]
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o"), "--cookies", str(bad)])
    assert result.exit_code == 2
    assert bad.name in result.stderr or which == "directory"
    assert SECRET not in result.stderr + result.stdout
    assert [c for c in container_media.calls() if "download" in c] == []


def test_a_bad_line_in_the_cookies_file_is_never_printed_in_process(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: True)
    bad = _bad_files(tmp_path)["badline"]
    result = runner.invoke(app, ["get", YOUTUBE, "--kind", "media", "-o", str(tmp_path / "o"), "--cookies", str(bad)])
    captured = capsys.readouterr()
    assert result.exit_code == 2
    assert SECRET not in result.stdout + result.stderr + captured.out + captured.err


def test_a_bad_cookies_file_from_the_setting_exits_2_too(
    container_media, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("ANYDM_COOKIES_FILE", str(tmp_path / "missing.txt"))
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o")])
    assert result.exit_code == 2
    assert "missing.txt" in result.stderr
    assert [c for c in container_media.calls() if "download" in c] == []


def test_a_file_others_can_read_gives_one_warning_and_the_download_goes_on(container_media, tmp_path: Path) -> None:
    container_media.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/v.mp4"]}])
    cookies = cookie_file(tmp_path, mode=0o644)
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o"), "--cookies", str(cookies)])
    assert result.exit_code == 0, result.stderr
    assert result.stderr.count("cookies file ") == 1
    assert "chmod 600" in " ".join(result.stderr.split())


def test_the_warning_is_silenced_by_quiet(container_media, tmp_path: Path) -> None:
    container_media.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/v.mp4"]}])
    cookies = cookie_file(tmp_path, mode=0o644)
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o"), "--quiet", "--cookies", str(cookies)])
    assert result.exit_code == 0, result.stderr
    assert result.stderr == ""


def test_a_private_file_gives_no_warning(container_media, tmp_path: Path) -> None:
    container_media.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/v.mp4"]}])
    cookies = cookie_file(tmp_path)
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(tmp_path / "o"), "--cookies", str(cookies)])
    assert result.exit_code == 0, result.stderr
    assert "cookies file" not in result.stderr


def test_no_cookie_value_reaches_stdout_stderr_the_docker_log_or_the_output_folder(
    container_media, tmp_path: Path
) -> None:
    container_media.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/v.mp4"]}])
    cookies = cookie_file(tmp_path, mode=0o644)
    out = tmp_path / "o"
    result = runner.invoke(app, ["get", YOUTUBE, "-o", str(out), "--cookies", str(cookies)])
    assert result.exit_code == 0, result.stderr
    assert SECRET not in result.stdout + result.stderr
    assert SECRET not in container_media.log.read_text()
    assert [f for f in out.iterdir() if SECRET in f.read_text(errors="replace")] == []


def test_a_cookies_file_is_ignored_for_a_plain_http_download(serve, tmp_path: Path) -> None:
    srv = serve(b"abc")
    cookies = cookie_file(tmp_path)
    result = runner.invoke(app, ["get", srv.url(), "-o", str(tmp_path / "o"), "--quiet", "--cookies", str(cookies)])
    assert result.exit_code == 0, result.stderr
    assert result.stderr == ""
    assert (tmp_path / "o" / "file.bin").read_bytes() == b"abc"
    assert [r.cookie for r in srv.log] != [] and all(r.cookie is None for r in srv.log)


def test_a_missing_cookies_file_does_not_affect_an_http_download(
    serve, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    srv = serve(b"abc")
    monkeypatch.setenv("ANYDM_COOKIES_FILE", str(tmp_path / "missing.txt"))
    result = runner.invoke(app, ["get", srv.url(), "-o", str(tmp_path / "o")])
    assert result.exit_code == 0, result.stderr
    assert "cookies" not in result.stderr
    assert (tmp_path / "o" / "file.bin").read_bytes() == b"abc"


def test_a_readable_by_others_cookies_file_gives_no_warning_for_an_http_download(
    serve, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    srv = serve(b"abc")
    monkeypatch.setenv("ANYDM_COOKIES_FILE", str(cookie_file(tmp_path, mode=0o644)))
    result = runner.invoke(app, ["get", srv.url(), "-o", str(tmp_path / "o")])
    assert result.exit_code == 0, result.stderr
    assert "cookies" not in result.stderr
