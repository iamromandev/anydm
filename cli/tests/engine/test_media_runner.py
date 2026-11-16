import io
import json
import sys
from pathlib import Path
from typing import Any

import pytest
import yt_dlp
from src import engine
from src.core.errors import DownloadError, PartialDownload
from src.engine import media_runner

from tests.engine.test_media import FakeYDL, one

URL = "https://www.youtube.com/watch?v=abc"


@pytest.fixture(autouse=True)
def fake(monkeypatch: pytest.MonkeyPatch) -> type[FakeYDL]:
    FakeYDL.opts, FakeYDL.info, FakeYDL.error, FakeYDL.hook_events = {}, {}, None, []
    monkeypatch.setattr(yt_dlp, "YoutubeDL", FakeYDL)
    return FakeYDL


@pytest.fixture(autouse=True)
def _folder(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(media_runner, "DOWNLOADS", tmp_path)


def run(*argv: str) -> tuple[int, list[dict[str, Any]]]:
    out = io.StringIO()
    code = media_runner.run(list(argv), out)
    return code, [json.loads(line) for line in out.getvalue().splitlines()]


def test_check_says_hello_then_the_answer(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(engine, "_local_media_supported", lambda url: "youtube" in url)
    code, lines = run("check", "--protocol", "1", "--url", URL)
    assert code == 0
    assert lines == [{"type": "hello", "protocol": 1}, {"type": "supported", "value": True}]


def test_download_reports_progress_then_done_with_container_paths(tmp_path: Path, fake: type[FakeYDL]) -> None:
    fake.info = one(tmp_path)
    fake.hook_events = [{"status": "downloading", "downloaded_bytes": 5, "total_bytes": 10, "speed": 2.0,
                         "filename": str(tmp_path / "v.part")}]
    code, lines = run("download", "--protocol", "1", "--url", URL)
    assert code == 0
    assert lines[0] == {"type": "hello", "protocol": 1}
    assert lines[1] == {"type": "progress", "done": 5, "total": 10, "speed": 2.0, "name": "v.part"}
    assert lines[-1] == {"type": "done", "paths": [str(tmp_path / "v [abc].mp4")]}


def test_download_passes_format_playlist_and_insecure(tmp_path: Path, fake: type[FakeYDL]) -> None:
    fake.info = one(tmp_path)
    run("download", "--protocol", "1", "--url", URL, "--format", "bv*", "--playlist", "--insecure")
    assert fake.opts["format"] == "bv*"
    assert fake.opts["noplaylist"] is False
    assert fake.opts["nocheckcertificate"] is True


def test_a_download_error_is_an_error_line_and_exit_1(fake: type[FakeYDL]) -> None:
    fake.error = DownloadError("boom")
    code, lines = run("download", "--protocol", "1", "--url", URL)
    assert code == 1
    assert lines[-1] == {"type": "error", "message": "boom", "partial": []}


def test_a_partial_download_lists_the_kept_paths(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    async def partial(self: object, job: object, report: object) -> list[Path]:
        raise PartialDownload("1 playlist entries failed", [tmp_path / "a.mp4"])

    monkeypatch.setattr(media_runner.MediaEngine, "run", partial)
    code, lines = run("download", "--protocol", "1", "--url", URL)
    assert code == 1
    assert lines[-1] == {"type": "error", "message": "1 playlist entries failed", "partial": [str(tmp_path / "a.mp4")]}


def test_interrupt_exits_130_without_an_error_line(monkeypatch: pytest.MonkeyPatch) -> None:
    async def stop(self: object, job: object, report: object) -> list[Path]:
        raise KeyboardInterrupt

    monkeypatch.setattr(media_runner.MediaEngine, "run", stop)
    code, lines = run("download", "--protocol", "1", "--url", URL)
    assert code == 130
    assert [ln["type"] for ln in lines] == ["hello"]


def test_an_unknown_protocol_is_refused() -> None:
    code, lines = run("download", "--protocol", "2", "--url", URL)
    assert code == 2
    assert lines == [{"type": "hello", "protocol": 1}]


def test_values_that_start_with_a_dash_are_taken_as_values_with_the_equals_form(
    tmp_path: Path, fake: type[FakeYDL]
) -> None:
    fake.info = one(tmp_path)
    code, _ = run("download", "--protocol", "1", "--url=-oops", "--format=-best")
    assert code == 0
    assert fake.opts["format"] == "-best"


def test_a_missing_ffmpeg_inside_the_image_does_not_point_at_anydm_doctor(fake: type[FakeYDL]) -> None:
    fake.error = yt_dlp.utils.DownloadError("ERROR: ffmpeg is not installed")
    code, lines = run("download", "--protocol", "1", "--url", URL)
    assert code == 1
    assert "ffmpeg" in lines[-1]["message"]
    assert "anydm doctor" not in lines[-1]["message"]


def test_a_check_starts_a_watchdog_that_ends_it_before_the_host_gives_up(monkeypatch: pytest.MonkeyPatch) -> None:
    from src.engine import docker

    started: list[float] = []
    monkeypatch.setattr(media_runner, "start_watchdog", lambda seconds, on_expire=None: started.append(seconds))
    monkeypatch.setattr(media_runner, "run", lambda argv, out: 0)
    monkeypatch.setattr(sys, "argv", ["media_runner", "check", "--protocol", "1", "--url", URL])
    with pytest.raises(SystemExit):
        media_runner.main()
    assert started == [media_runner.CHECK_LIFETIME]
    assert media_runner.CHECK_LIFETIME < docker.CHECK_TIMEOUT
    assert media_runner.CHECK_LIFETIME == docker.CHECK_LIFETIME


def test_a_download_has_no_watchdog(monkeypatch: pytest.MonkeyPatch) -> None:
    started: list[float] = []
    monkeypatch.setattr(media_runner, "start_watchdog", lambda seconds, on_expire=None: started.append(seconds))
    monkeypatch.setattr(media_runner, "run", lambda argv, out: 0)
    monkeypatch.setattr(sys, "argv", ["media_runner", "download", "--protocol", "1", "--url", URL])
    with pytest.raises(SystemExit):
        media_runner.main()
    assert started == []


def test_running_a_check_never_arms_the_process_ending_watchdog(monkeypatch: pytest.MonkeyPatch) -> None:
    """run() is what tests call in-process: a watchdog there would end the whole test run 50 s later."""
    started: list[float] = []
    monkeypatch.setattr(media_runner, "start_watchdog", lambda seconds, on_expire=None: started.append(seconds))
    monkeypatch.setattr(engine, "_local_media_supported", lambda url: False)
    run("check", "--protocol", "1", "--url", URL)
    assert started == []


def test_the_watchdog_fires_after_its_time_and_not_before() -> None:
    import threading

    fired = threading.Event()
    media_runner.start_watchdog(0.2, fired.set)
    assert not fired.wait(0.05)
    assert fired.wait(2)
