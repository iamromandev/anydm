import asyncio
import os
import time
from pathlib import Path
from typing import Any

import pytest
from src.core.errors import DownloadError, PartialDownload
from src.engine import Job
from src.plugin.engine import PluginEngine
from src.plugin.runner import PluginRunner

from tests.conftest import FakeDocker
from tests.engine.test_media import Collect

IMAGE = "anydm-media:0.0.1"
URL = "https://www.youtube.com/watch?v=abc"
HELLO = {"type": "hello", "protocol": 3}


def job(dest: Path, **kw: Any) -> Job:
    base: dict[str, Any] = {"source": URL, "dest_dir": dest, "connections": 4, "format": None,
                            "playlist": False, "seed": False, "insecure": False}
    return Job(**{**base, **kw})


def engine() -> PluginEngine:
    return PluginEngine(PluginRunner(os.environ["ANYDM_DOCKER"], IMAGE))


def run_call(fake_docker: FakeDocker) -> list[str]:
    [call] = [c for c in fake_docker.calls() if c[0] == "run"]
    return call


async def test_progress_notices_and_host_paths(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[
        HELLO,
        {"type": "progress", "done": 5, "total": 10, "speed": 2.0, "name": "v.part"},
        {"type": "notice", "message": "careful"},
        {"type": "done", "paths": ["/downloads/v [abc].mp4"]},
    ])
    report = Collect()
    paths = await engine().run(job(tmp_path), report)
    assert paths == [tmp_path.resolve() / "v [abc].mp4"]
    assert [(p.done, p.total, p.speed, p.name) for p in report.updates] == [(5, 10, 2.0, "v.part")]
    assert report.notices == ["careful"]


async def test_command_line_is_locked_down(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/a.mp4"]}])
    await engine().run(job(tmp_path, format="bv*+ba", playlist=True, insecure=True), Collect())
    call = run_call(fake_docker)
    assert "--rm" in call and "anydm=media" in call
    assert call[call.index("--user") + 1] == f"{os.getuid()}:{os.getgid()}"
    assert call.count("-v") == 1
    assert [call[i + 1] for i, a in enumerate(call) if a == "-e"] == ["HOME=/tmp"]
    assert "--privileged" not in call
    i = call.index(IMAGE)
    assert call[i - 1] == "--"
    assert call[i:] == [IMAGE, "download", "--protocol", "3", f"--url={URL}", "--format=bv*+ba", "--playlist",
                        "--insecure"]


async def test_defaults_send_no_optional_flags(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/a.mp4"]}])
    await engine().run(job(tmp_path), Collect())
    assert run_call(fake_docker)[-4:] == ["download", "--protocol", "3", f"--url={URL}"]


async def test_an_error_line_is_a_download_error(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "error", "message": "boom", "partial": []}], exit=1)
    with pytest.raises(DownloadError, match="boom") as ei:
        await engine().run(job(tmp_path), Collect())
    assert not isinstance(ei.value, PartialDownload)


async def test_an_error_line_with_partial_paths_is_a_partial_download(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "error", "message": "1 failed", "partial": ["/downloads/a.mp4"]}], exit=1)
    with pytest.raises(PartialDownload) as ei:
        await engine().run(job(tmp_path), Collect())
    assert ei.value.paths == [tmp_path.resolve() / "a.mp4"]


@pytest.mark.parametrize("bad", ["/etc/passwd", "/downloads/../x", "relative.mp4", "/downloadsX/a.mp4", "/downloads"])
async def test_a_path_outside_the_folder_is_refused(fake_docker: FakeDocker, tmp_path: Path, bad: str) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": [bad]}])
    with pytest.raises(DownloadError, match="outside"):
        await engine().run(job(tmp_path), Collect())


async def test_a_symlink_escaping_the_folder_is_refused(fake_docker: FakeDocker, tmp_path: Path) -> None:
    dest = tmp_path / "dest"
    dest.mkdir()
    (dest / "link.mp4").symlink_to(tmp_path / "elsewhere")
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/link.mp4"]}])
    with pytest.raises(DownloadError, match="outside"):
        await engine().run(job(dest), Collect())


async def test_a_different_protocol_asks_for_an_update(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[{"type": "hello", "protocol": 1}, {"type": "done", "paths": ["/downloads/a.mp4"]}])
    with pytest.raises(DownloadError, match="update the media image"):
        await engine().run(job(tmp_path), Collect())


async def test_no_hello_asks_for_an_update(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[{"type": "done", "paths": ["/downloads/a.mp4"]}])
    with pytest.raises(DownloadError, match="update the media image"):
        await engine().run(job(tmp_path), Collect())


async def test_junk_and_oversized_lines_are_ignored(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[
        "not json", "[1, 2]", "x" * (2 << 20), HELLO, "{broken",
        {"type": "progress", "done": "NaN?", "total": None, "speed": 0, "name": None},
        {"type": "done", "paths": ["/downloads/a.mp4"]},
    ])
    paths = await engine().run(job(tmp_path), Collect())
    assert paths == [tmp_path.resolve() / "a.mp4"]


async def test_a_container_that_dies_without_a_result_reports_stderr(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO], exit=137, stderr="Killed")
    with pytest.raises(DownloadError, match=r"137.*Killed"):
        await engine().run(job(tmp_path), Collect())


async def test_a_failed_start_shows_docker_stderr_with_a_sharing_hint(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[], exit=125, stderr="docker: Error response: mounts denied: path is not shared")
    with pytest.raises(DownloadError, match=r"mounts denied.*shared"):
        await engine().run(job(tmp_path), Collect())


async def test_stderr_in_the_error_is_bounded(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO], exit=1, stderr="e" * 50000)
    with pytest.raises(DownloadError) as ei:
        await engine().run(job(tmp_path), Collect())
    assert len(str(ei.value)) < 2500


async def test_a_missing_image_is_reported_before_any_container_starts(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(image=False)
    with pytest.raises(DownloadError, match="make build-media"):
        await engine().run(job(tmp_path), Collect())
    assert "run" not in fake_docker.commands()


async def test_no_docker_is_reported_naming_doctor(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(version="fail")
    with pytest.raises(DownloadError, match=r"Docker.*anydm doctor"):
        await engine().run(job(tmp_path), Collect())


async def test_cancel_kills_and_removes_the_container_within_2_seconds(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO], hang=True)
    task = asyncio.create_task(engine().run(job(tmp_path), Collect()))
    while "run" not in fake_docker.commands():
        await asyncio.sleep(0.01)
    await asyncio.sleep(0.2)
    start = time.monotonic()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert time.monotonic() - start < 2
    name = run_call(fake_docker)[run_call(fake_docker).index("--name") + 1]
    assert ["kill", "--signal", "INT", name] in fake_docker.calls()
    assert ["rm", "-f", name] in fake_docker.calls()


async def test_cancel_still_returns_in_time_when_the_container_ignores_the_kill(
    fake_docker: FakeDocker, tmp_path: Path
) -> None:
    fake_docker.set(lines=[HELLO], hang=True, ignore_kill=True)
    task = asyncio.create_task(engine().run(job(tmp_path), Collect()))
    while "run" not in fake_docker.commands():
        await asyncio.sleep(0.01)
    await asyncio.sleep(0.2)
    start = time.monotonic()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert time.monotonic() - start < 2
    assert len(fake_docker.marked("removed")) == 1


async def started(fake_docker: FakeDocker, task: asyncio.Task[object]) -> str:
    """The name of the container once `docker run` was called."""
    while "run" not in fake_docker.commands():
        assert not task.done()
        await asyncio.sleep(0.01)
    return run_call(fake_docker)[run_call(fake_docker).index("--name") + 1]


async def cancel_timed(task: asyncio.Task[object]) -> float:
    start = time.monotonic()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    return time.monotonic() - start


async def test_cancel_before_the_container_exists_removes_it_once_it_does_within_1_5_seconds(
    fake_docker: FakeDocker, tmp_path: Path
) -> None:
    fake_docker.set(lines=[HELLO], hang=True, start_delay=0.5)
    task = asyncio.create_task(engine().run(job(tmp_path), Collect()))
    name = await started(fake_docker, task)
    await asyncio.sleep(0.1)
    took = await cancel_timed(task)
    assert took < 1.6
    assert fake_docker.marked("removed") == [name]
    calls = fake_docker.calls()
    assert calls.count(["kill", "--signal", "INT", name]) == 1  # tried once, then straight to rm -f
    assert calls.count(["rm", "-f", name]) >= 2  # retried until the container showed up


async def test_cancel_gives_up_within_1_5_seconds_when_the_container_never_appears(
    fake_docker: FakeDocker, tmp_path: Path
) -> None:
    fake_docker.set(lines=[HELLO], hang=True, start_delay=30)
    task = asyncio.create_task(engine().run(job(tmp_path), Collect()))
    await started(fake_docker, task)
    took = await cancel_timed(task)
    assert 1.0 < took < 1.8


async def test_cancel_stops_the_docker_client_after_removing_the_container(
    fake_docker: FakeDocker, tmp_path: Path
) -> None:
    """A container that ignores the kill is removed with rm -f; the `docker run` client is then killed, not waited for."""
    fake_docker.set(lines=[HELLO], hang=True, ignore_kill=True)
    task = asyncio.create_task(engine().run(job(tmp_path), Collect()))
    name = await started(fake_docker, task)
    await asyncio.sleep(0.2)
    took = await cancel_timed(task)
    assert took < 1.7
    assert fake_docker.marked("removed") == [name]


async def test_a_second_cancel_while_stopping_still_removes_the_container(
    fake_docker: FakeDocker, tmp_path: Path
) -> None:
    fake_docker.set(lines=[HELLO], hang=True, ignore_kill=True)
    task = asyncio.create_task(engine().run(job(tmp_path), Collect()))
    name = await started(fake_docker, task)
    await asyncio.sleep(0.2)
    task.cancel()
    await asyncio.sleep(0.1)  # the first cancel is waiting for the container to stop
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert fake_docker.marked("removed") == [name]


@pytest.mark.parametrize("paths", ["abc", {"a": 1}, [1], [None], ["/downloads/a", 3], ["/downloads/a\x00b"]])
async def test_odd_paths_from_the_container_are_a_download_error(
    fake_docker: FakeDocker, tmp_path: Path, paths: object
) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": paths}])
    with pytest.raises(DownloadError):
        await engine().run(job(tmp_path), Collect())


@pytest.mark.parametrize("partial", ["abc", [1], ["/downloads/a\x00b"]])
async def test_odd_partial_paths_from_the_container_are_a_download_error(
    fake_docker: FakeDocker, tmp_path: Path, partial: object
) -> None:
    fake_docker.set(lines=[HELLO, {"type": "error", "message": "x", "partial": partial}], exit=1)
    with pytest.raises(DownloadError) as ei:
        await engine().run(job(tmp_path), Collect())
    assert not isinstance(ei.value, PartialDownload)


async def test_the_stale_container_sweep_runs_once_per_runner(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/a.mp4"]}])
    eng = engine()
    await eng.run(job(tmp_path), Collect())
    await eng.run(job(tmp_path), Collect())
    assert fake_docker.commands().count("ps") == 1


async def test_as_root_the_download_is_refused_before_any_container_starts(
    fake_docker: FakeDocker, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(os, "getuid", lambda: 0)
    with pytest.raises(DownloadError, match="root"):
        await engine().run(job(tmp_path), Collect())
    assert "run" not in fake_docker.commands()


SECRET = "s3cr3t-session-value-7f3a"
TARGET = "/run/anydm/cookies.txt"


@pytest.fixture
def cookies(tmp_path: Path) -> Path:
    f = tmp_path / "cookies.txt"
    f.write_text(f"# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tFALSE\t2000000000\tsid\t{SECRET}\n")
    return f


async def test_a_job_with_cookies_mounts_the_file_read_only_and_says_so(
    fake_docker: FakeDocker, tmp_path: Path, cookies: Path
) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/a.mp4"]}])
    out = tmp_path / "out"
    out.mkdir()
    await engine().run(job(out, cookies=cookies), Collect())
    call = run_call(fake_docker)
    mounts = [call[i + 1] for i, a in enumerate(call) if a == "--mount"]
    assert mounts == [f"type=bind,src={cookies},dst={TARGET},readonly"]
    assert not TARGET.startswith("/downloads")
    assert call.count("-v") == 1  # the output folder
    i = call.index(IMAGE)
    assert call[i:] == [IMAGE, "download", "--protocol", "3", f"--url={URL}", "--cookies"]


async def test_no_cookie_value_is_in_the_docker_command_line_or_the_log(
    fake_docker: FakeDocker, tmp_path: Path, cookies: Path
) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/a.mp4"]}])
    await engine().run(job(tmp_path, cookies=cookies), Collect())
    assert SECRET not in fake_docker.log.read_text()


async def test_without_cookies_there_is_no_mount_and_no_flag(fake_docker: FakeDocker, tmp_path: Path) -> None:
    fake_docker.set(lines=[HELLO, {"type": "done", "paths": ["/downloads/a.mp4"]}])
    await engine().run(job(tmp_path), Collect())
    call = run_call(fake_docker)
    assert "--mount" not in call
    assert "--cookies" not in call


async def test_a_cookies_path_with_a_comma_fails_before_any_container_starts(
    fake_docker: FakeDocker, tmp_path: Path
) -> None:
    bad = tmp_path / "a,b.txt"
    bad.write_text("x")
    with pytest.raises(DownloadError, match="cookies"):
        await engine().run(job(tmp_path, cookies=bad), Collect())
    assert [c for c in fake_docker.calls() if c[0] == "run"] == []


async def test_a_login_error_from_the_container_is_shown_as_it_came(fake_docker: FakeDocker, tmp_path: Path) -> None:
    msg = "Sign in to confirm your age; this site needs a login: pass --cookies FILE (see README)"
    fake_docker.set(lines=[HELLO, {"type": "error", "message": msg, "partial": []}], exit=1)
    with pytest.raises(DownloadError) as e:
        await engine().run(job(tmp_path), Collect())
    assert str(e.value) == msg


def test_the_check_container_never_gets_the_cookies(fake_docker: FakeDocker, cookies: Path) -> None:
    # the check has no way to be given them: `supported` takes only the URL
    PluginRunner(os.environ["ANYDM_DOCKER"], IMAGE).supported(URL)
    [call] = [c for c in fake_docker.calls() if c[0] == "run"]
    assert "--mount" not in call
    assert "--cookies" not in call
    assert SECRET not in fake_docker.log.read_text()
