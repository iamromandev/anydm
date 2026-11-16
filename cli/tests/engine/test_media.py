import asyncio
import threading
import time
from pathlib import Path
from typing import Any, ClassVar

import pytest
import yt_dlp
from src import engine
from src.core.errors import DownloadError
from src.core.progress import Progress
from src.engine import Job, Kind, engine_for, resolve
from src.engine.media import MediaEngine
from src.main import app
from typer.testing import CliRunner

runner = CliRunner()
URL = "https://www.youtube.com/watch?v=abc"


class Collect:
    def __init__(self) -> None:
        self.updates: list[Progress] = []
        self.notices: list[str] = []

    def update(self, p: Progress) -> None:
        self.updates.append(p)

    def notice(self, msg: str) -> None:
        self.notices.append(msg)


class FakeYDL:
    opts: ClassVar[dict[str, Any]] = {}
    info: ClassVar[dict[str, Any]] = {}
    error: Exception | None = None
    hook_events: ClassVar[list[dict[str, Any]]] = []

    def __init__(self, opts: dict[str, Any]) -> None:
        FakeYDL.opts = opts

    def __enter__(self) -> FakeYDL:
        return self

    def __exit__(self, *a: object) -> None:
        return None

    def extract_info(self, url: str, download: bool = True) -> dict[str, Any]:
        assert download is True
        for ev in FakeYDL.hook_events:
            for hook in self.opts["progress_hooks"]:
                hook(ev)
        if FakeYDL.error:
            raise FakeYDL.error
        return FakeYDL.info


@pytest.fixture(autouse=True)
def fake(monkeypatch: pytest.MonkeyPatch) -> type[FakeYDL]:
    FakeYDL.opts, FakeYDL.info, FakeYDL.error, FakeYDL.hook_events = {}, {}, None, []
    monkeypatch.setattr(yt_dlp, "YoutubeDL", FakeYDL)
    return FakeYDL


def job(dest: Path, **kw: Any) -> Job:
    base: dict[str, Any] = {"source": URL, "dest_dir": dest, "connections": 4, "format": None,
                            "playlist": False, "seed": False, "insecure": False}
    return Job(**{**base, **kw})


def one(dest: Path, name: str = "v [abc].mp4") -> dict[str, Any]:
    return {"requested_downloads": [{"filepath": str(dest / name)}]}


def test_resolver_real_extractors_no_network() -> None:
    assert resolve(URL, None) is Kind.MEDIA
    assert resolve("https://example.com/files/a.iso", None) is Kind.HTTP


def test_engine_for_media() -> None:
    assert isinstance(engine_for(Kind.MEDIA), MediaEngine)


async def test_default_opts(fake, tmp_path: Path) -> None:
    fake.info = one(tmp_path)
    paths = await MediaEngine().run(job(tmp_path), Collect())
    assert paths == [tmp_path / "v [abc].mp4"]
    o = fake.opts
    assert o["paths"] == {"home": str(tmp_path)}
    assert o["outtmpl"] == "%(title)s [%(id)s].%(ext)s"
    assert "format" not in o
    assert o["noplaylist"] is True
    assert o["quiet"] and o["noprogress"]
    assert o["nocheckcertificate"] is False


async def test_format_playlist_insecure_opts(fake, tmp_path: Path) -> None:
    fake.info = one(tmp_path)
    await MediaEngine().run(job(tmp_path, format="bestaudio", playlist=True, insecure=True), Collect())
    assert fake.opts["format"] == "bestaudio"
    assert fake.opts["noplaylist"] is False
    assert fake.opts["nocheckcertificate"] is True


async def test_playlist_returns_every_item(fake, tmp_path: Path) -> None:
    fake.info = {"entries": [one(tmp_path, "a.mp4"), one(tmp_path, "b.mp4")]}
    paths = await MediaEngine().run(job(tmp_path, playlist=True), Collect())
    assert paths == [tmp_path / "a.mp4", tmp_path / "b.mp4"]


async def test_single_item_from_playlist_url(fake, tmp_path: Path) -> None:
    fake.info = one(tmp_path, "a.mp4")
    assert await MediaEngine().run(job(tmp_path), Collect()) == [tmp_path / "a.mp4"]


@pytest.mark.parametrize("exc", [yt_dlp.utils.DownloadError("boom"), yt_dlp.utils.ExtractorError("bad")])
async def test_yt_dlp_errors_become_download_error(fake, tmp_path: Path, exc: Exception) -> None:
    fake.error = exc
    with pytest.raises(DownloadError):
        await MediaEngine().run(job(tmp_path), Collect())


async def test_missing_ffmpeg_points_at_doctor(fake, tmp_path: Path) -> None:
    fake.error = yt_dlp.utils.DownloadError("ERROR: ffmpeg is not installed")
    with pytest.raises(DownloadError, match="anydm doctor"):
        await MediaEngine().run(job(tmp_path), Collect())


async def test_progress_mapping(fake, tmp_path: Path) -> None:
    fake.info = one(tmp_path)
    fake.hook_events = [
        {"status": "downloading", "downloaded_bytes": 50, "total_bytes": 200, "speed": 10.0, "filename": "x/v.mp4"},
        {"status": "downloading", "downloaded_bytes": 60, "total_bytes_estimate": 300, "speed": None, "filename": "v.mp4"},
    ]
    rep = Collect()
    await MediaEngine().run(job(tmp_path), rep)
    assert rep.updates[0] == Progress(50, 200, 10.0, name="v.mp4")
    assert rep.updates[1] == Progress(60, 300, 0.0, name="v.mp4")


@pytest.mark.parametrize("bad", ["../evil.mp4", "/etc/passwd"])
async def test_path_outside_dest_is_rejected(fake, tmp_path: Path, bad: str) -> None:
    dest = tmp_path / "dest"
    dest.mkdir()
    fake.info = {"requested_downloads": [{"filepath": str(dest / bad) if bad.startswith("..") else bad}]}
    with pytest.raises(DownloadError, match="outside"):
        await MediaEngine().run(job(dest), Collect())


async def test_cancel_stops_the_worker(fake, tmp_path: Path) -> None:
    fake.info = one(tmp_path)
    task = asyncio.create_task(MediaEngine().run(job(tmp_path), Collect()))
    await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task


async def test_cancel_returns_quickly_when_the_hook_never_fires(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    release = threading.Event()

    class Blocking(FakeYDL):
        def extract_info(self, url: str, download: bool = True) -> dict[str, Any]:
            release.wait(30)
            return {}

    monkeypatch.setattr(yt_dlp, "YoutubeDL", Blocking)
    task = asyncio.create_task(MediaEngine().run(job(tmp_path), Collect()))
    await asyncio.sleep(0.2)
    t = time.monotonic()
    task.cancel()
    try:
        with pytest.raises(asyncio.CancelledError):
            await task
        assert time.monotonic() - t < 1
    finally:
        release.set()


async def test_cancel_sets_stop_and_later_hook_call_raises(fake, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    hooks: list[Any] = []
    started = threading.Event()
    release = threading.Event()

    class Waiting(FakeYDL):
        def extract_info(self, url: str, download: bool = True) -> dict[str, Any]:
            hooks.extend(self.opts["progress_hooks"])
            started.set()
            release.wait(30)
            return {}

    monkeypatch.setattr(yt_dlp, "YoutubeDL", Waiting)
    task = asyncio.create_task(MediaEngine().run(job(tmp_path), Collect()))
    while not started.is_set():
        await asyncio.sleep(0.01)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    try:
        with pytest.raises(yt_dlp.utils.DownloadCancelled):
            hooks[0]({"status": "downloading"})
    finally:
        release.set()


async def test_playlist_items_limited_without_playlist_flag(fake, tmp_path: Path) -> None:
    fake.info = one(tmp_path)
    await MediaEngine().run(job(tmp_path), Collect())
    assert fake.opts["playlist_items"] == "1"
    await MediaEngine().run(job(tmp_path, playlist=True), Collect())
    assert "playlist_items" not in fake.opts


async def test_safe_opts(fake, tmp_path: Path) -> None:
    fake.info = one(tmp_path)
    await MediaEngine().run(job(tmp_path), Collect())
    assert fake.opts["socket_timeout"] == 20
    assert fake.opts["windowsfilenames"] is True
    assert "ignoreerrors" not in fake.opts
    await MediaEngine().run(job(tmp_path, playlist=True), Collect())
    assert fake.opts["ignoreerrors"] == "only_download"


async def test_logger_drops_noise_and_forwards_warnings(fake, tmp_path: Path) -> None:
    fake.info = one(tmp_path)
    rep = Collect()
    await MediaEngine().run(job(tmp_path), rep)
    log = fake.opts["logger"]
    log.debug("d")
    log.info("i")
    log.error("e")
    log.warning("careful")
    assert rep.notices == ["careful"]


async def test_oserror_becomes_download_error(fake, tmp_path: Path) -> None:
    fake.error = PermissionError("disk full")
    with pytest.raises(DownloadError, match="disk full"):
        await MediaEngine().run(job(tmp_path), Collect())


async def test_reporter_exception_in_hook_is_dropped(fake, tmp_path: Path) -> None:
    class Bad(Collect):
        def update(self, p: Progress) -> None:
            raise RuntimeError("terminal gone")

    fake.info = one(tmp_path)
    fake.hook_events = [{"status": "downloading", "downloaded_bytes": 1, "filename": "v.mp4"}]
    assert await MediaEngine().run(job(tmp_path), Bad()) == [tmp_path / "v [abc].mp4"]


async def test_partial_playlist_reports_failures_and_keeps_paths(fake, tmp_path: Path) -> None:
    from src.core.errors import PartialDownload

    fake.info = {"entries": [one(tmp_path, "a.mp4"), None, one(tmp_path, "c.mp4")]}
    rep = Collect()
    with pytest.raises(PartialDownload) as ei:
        await MediaEngine().run(job(tmp_path, playlist=True), rep)
    assert ei.value.paths == [tmp_path / "a.mp4", tmp_path / "c.mp4"]
    assert len(rep.notices) == 1
    assert "entry 2" in rep.notices[0]


def test_cli_partial_playlist_prints_paths_and_exits_1(fake, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    out = (tmp_path / "out").resolve()
    fake.info = {"entries": [one(out, "a.mp4"), None]}
    result = runner.invoke(app, ["get", URL, "-o", str(out), "--quiet", "--playlist"])
    assert result.exit_code == 1
    assert result.stdout == f"{out / 'a.mp4'}\n"


def test_cli_get_media_prints_paths(fake, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    out = tmp_path / "out"
    fake.info = one(out.resolve())
    result = runner.invoke(app, ["get", URL, "-o", str(out), "--quiet", "-f", "bestaudio", "--playlist"])
    assert result.exit_code == 0, result.stderr
    assert result.stdout == f"{out.resolve() / 'v [abc].mp4'}\n"
    assert fake.opts["format"] == "bestaudio"
    assert fake.opts["noplaylist"] is False


@pytest.mark.live
def test_live_extractor_matches_real_site() -> None:
    assert engine._media_supported(URL)


class SlowToStop(FakeYDL):
    """First call: calls the progress hook until it raises, then takes `cleanup` seconds to wind down."""

    calls = 0
    cleanup = 0.3
    dest = Path()
    live = 0
    peak = 0
    ended = threading.Event()
    lock = threading.Lock()

    def extract_info(self, url: str, download: bool = True) -> dict[str, Any]:
        cls = SlowToStop
        with cls.lock:
            cls.calls += 1
            first = cls.calls == 1
            cls.live += 1
            cls.peak = max(cls.peak, cls.live)
        try:
            if not first:
                return one(cls.dest)
            while True:
                time.sleep(0.02)
                try:
                    self.opts["progress_hooks"][0]({"status": "downloading", "downloaded_bytes": 1})
                except yt_dlp.utils.DownloadCancelled:
                    time.sleep(cls.cleanup)
                    raise
        finally:
            with cls.lock:
                cls.live -= 1
            if first:
                cls.ended.set()


@pytest.fixture
def slow(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> type[SlowToStop]:
    SlowToStop.calls, SlowToStop.live, SlowToStop.peak, SlowToStop.cleanup = 0, 0, 0, 0.3
    SlowToStop.ended = threading.Event()
    SlowToStop.dest = tmp_path
    monkeypatch.setattr(yt_dlp, "YoutubeDL", SlowToStop)
    return SlowToStop


async def test_cancel_waits_for_the_worker_thread_to_finish(slow, tmp_path: Path) -> None:
    task = asyncio.create_task(MediaEngine().run(job(tmp_path), Collect()))
    await asyncio.sleep(0.2)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert slow.ended.is_set(), "run() returned while yt-dlp was still writing"


async def test_retry_during_a_slow_cancel_never_runs_two_threads_at_once(
    slow, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr("src.engine.media.CANCEL_WAIT", 0.1)
    slow.cleanup = 0.6  # longer than the bounded wait
    first = asyncio.create_task(MediaEngine().run(job(tmp_path), Collect()))
    await asyncio.sleep(0.2)
    first.cancel()
    with pytest.raises(asyncio.CancelledError):
        await first
    assert not slow.ended.is_set()  # the bounded wait gave up
    paths = await MediaEngine().run(job(tmp_path), Collect())  # the retry
    assert paths
    assert slow.peak == 1
    assert slow.ended.is_set()
