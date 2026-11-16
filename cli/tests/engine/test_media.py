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


class _Http416(Exception):
    """What yt-dlp's DownloadError wraps for a server that answers 416 to a resume at the end of the file."""

    status = 416

    def __init__(self, total: int) -> None:
        super().__init__("HTTP Error 416: Range Not Satisfiable")
        self.response = type("R", (), {"headers": {"Content-Range": f"bytes */{total}"}})()


def _answers_416_once(monkeypatch: pytest.MonkeyPatch, dest: Path, total: int) -> list[int]:
    """yt-dlp that finds the target's .part and is told 416 the first time, then finds the finished file."""
    calls: list[int] = []

    class YDL(FakeYDL):
        def prepare_filename(self, info: dict[str, Any]) -> str:
            return str(dest / "v [abc].mp4")

        def extract_info(self, url: str, download: bool = True) -> dict[str, Any]:
            calls.append(1)
            self.opts["match_filter"]({"id": "abc"})
            if len(calls) == 1:
                cause = _Http416(total)
                raise yt_dlp.utils.DownloadError("unable to download video data: 416", exc_info=(type(cause), cause, None))
            assert (dest / "v [abc].mp4").read_bytes() == b"x" * total  # the complete .part became the file
            return one(dest)

    monkeypatch.setattr(yt_dlp, "YoutubeDL", YDL)
    return calls


async def test_a_complete_part_file_that_the_server_answers_416_for_is_adopted(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Ctrl-C after the last byte but before the rename leaves a complete .part; a standard server answers 416 to the
    resume, which yt-dlp does not handle on its own. The run that follows must still finish."""
    (tmp_path / "v [abc].mp4.part").write_bytes(b"x" * 100)
    calls = _answers_416_once(monkeypatch, tmp_path, 100)
    paths = await MediaEngine().run(job(tmp_path), Collect())
    assert paths == [tmp_path / "v [abc].mp4"]
    assert not (tmp_path / "v [abc].mp4.part").exists()
    assert len(calls) == 2


async def test_a_416_for_a_part_file_of_another_size_is_still_an_error(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    (tmp_path / "v [abc].mp4.part").write_bytes(b"x" * 99)
    calls = _answers_416_once(monkeypatch, tmp_path, 100)
    with pytest.raises(DownloadError, match="416"):
        await MediaEngine().run(job(tmp_path), Collect())
    assert (tmp_path / "v [abc].mp4.part").exists()
    assert len(calls) == 1


SECRET = "s3cr3t-session-value-7f3a"
COOKIES_TEXT = f"# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tFALSE\t2000000000\tsid\t{SECRET}\n"


@pytest.fixture
def cookie_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """A cookies file of the user, and a temp folder of its own that the private copies go to."""
    scratch = tmp_path / "tmp"
    scratch.mkdir()
    monkeypatch.setattr("tempfile.tempdir", str(scratch))
    f = tmp_path / "cookies.txt"
    f.write_text(COOKIES_TEXT)
    f.chmod(0o644)
    return f


def _scratch(tmp_path: Path) -> list[Path]:
    return list((tmp_path / "tmp").glob("anydm-cookies-*"))


class SeesCookies(FakeYDL):
    """Records what yt-dlp is given as its cookie file while it runs."""

    seen: ClassVar[dict[str, Any]] = {}

    def extract_info(self, url: str, download: bool = True) -> dict[str, Any]:
        path = Path(self.opts["cookiefile"])
        SeesCookies.seen = {"path": path, "text": path.read_text(), "mode": path.stat().st_mode & 0o777}
        return super().extract_info(url, download)


async def test_no_cookies_means_no_cookiefile_option(fake, tmp_path: Path) -> None:
    fake.info = one(tmp_path)
    await MediaEngine().run(job(tmp_path), Collect())
    assert "cookiefile" not in fake.opts


async def test_yt_dlp_gets_a_private_copy_of_the_cookies_that_is_removed_afterwards(
    fake, monkeypatch: pytest.MonkeyPatch, tmp_path: Path, cookie_file: Path
) -> None:
    fake.info = one(tmp_path)
    monkeypatch.setattr(yt_dlp, "YoutubeDL", SeesCookies)
    await MediaEngine().run(job(tmp_path, cookies=cookie_file), Collect())
    seen = SeesCookies.seen
    assert seen["path"] != cookie_file
    assert seen["path"].parent == tmp_path / "tmp"
    assert seen["text"] == COOKIES_TEXT
    assert seen["mode"] == 0o600
    assert not seen["path"].exists()
    assert _scratch(tmp_path) == []
    assert cookie_file.read_text() == COOKIES_TEXT


async def test_the_copy_is_removed_when_yt_dlp_fails(fake, tmp_path: Path, cookie_file: Path) -> None:
    fake.error = yt_dlp.utils.DownloadError("boom")
    with pytest.raises(DownloadError):
        await MediaEngine().run(job(tmp_path, cookies=cookie_file), Collect())
    assert _scratch(tmp_path) == []


async def test_the_copy_is_removed_after_a_cancel(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, cookie_file: Path
) -> None:
    started = threading.Event()

    class Waiting(FakeYDL):
        def extract_info(self, url: str, download: bool = True) -> dict[str, Any]:
            started.set()
            while True:
                time.sleep(0.01)
                self.opts["progress_hooks"][0]({"status": "downloading"})

    monkeypatch.setattr(yt_dlp, "YoutubeDL", Waiting)
    task = asyncio.create_task(MediaEngine().run(job(tmp_path, cookies=cookie_file), Collect()))
    while not started.is_set():
        await asyncio.sleep(0.01)
    assert len(_scratch(tmp_path)) == 1
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    for _ in range(100):  # the thread ends a moment after run() returns
        if not _scratch(tmp_path):
            break
        await asyncio.sleep(0.02)
    assert _scratch(tmp_path) == []


async def test_a_cookies_file_that_vanished_is_a_download_error_without_content(
    fake, tmp_path: Path, cookie_file: Path
) -> None:
    cookie_file.unlink()
    with pytest.raises(DownloadError, match="cookies") as e:
        await MediaEngine().run(job(tmp_path, cookies=cookie_file), Collect())
    assert SECRET not in str(e.value)


LOGIN_MSG = "ERROR: [vimeo] 123: Sign in to confirm your age. Use --cookies-from-browser or --cookies for the authentication"


def test_a_login_error_without_cookies_points_at_the_cookies_flag() -> None:
    from src.engine.media import _map_error

    err = _map_error(yt_dlp.utils.DownloadError(LOGIN_MSG))
    assert "Sign in to confirm your age" in str(err)
    assert str(err).endswith("; this site needs a login: pass --cookies FILE (see README)")


def test_a_login_error_with_cookies_says_they_may_be_expired() -> None:
    from src.engine.media import _map_error

    err = _map_error(yt_dlp.utils.DownloadError(LOGIN_MSG), cookies=Path("c.txt"))
    assert str(err).endswith("; the cookies may be expired: export them again")
    assert "pass --cookies" not in str(err)


@pytest.mark.parametrize("msg", ["Private video. Sign in if you've been granted access", "members-only content",
                                 "This video is age-restricted", "Please log in", "login required"])
def test_the_other_login_wordings_get_the_hint_too(msg: str) -> None:
    from src.engine.media import _map_error

    assert "--cookies FILE" in str(_map_error(yt_dlp.utils.DownloadError(msg)))


@pytest.mark.parametrize("msg", [
    "HTTP Error 404: Not Found for https://example.com/login.mp4",
    "Unable to download webpage: https://example.com/blog/login-page.html (404)",
    "unable to read cookies.sqlite",
    "Unsupported URL: https://example.com/catalog/author/x",
])
def test_an_error_that_only_mentions_a_word_like_login_in_a_url_gets_no_hint(msg: str) -> None:
    from src.engine.media import _map_error

    assert "--cookies" not in str(_map_error(yt_dlp.utils.DownloadError(msg)))


@pytest.mark.parametrize("msg", ["Sign in to confirm you're not a bot", "Login required", "This is a Private video",
                                 "Members-only content", "Age-restricted video", "Authentication required"])
def test_the_login_phrases_get_the_hint(msg: str) -> None:
    from src.engine.media import _map_error

    assert "--cookies FILE" in str(_map_error(yt_dlp.utils.DownloadError(msg)))


def test_a_cookie_load_error_says_the_file_could_not_be_parsed_without_a_login_hint(tmp_path: Path) -> None:
    from src.engine.media import _map_error
    from yt_dlp.cookies import CookieLoadError

    path = tmp_path / "c.txt"
    err = _map_error(CookieLoadError("failed to load cookies"), cookies=path)
    assert str(err) == f"cookies file could not be parsed: {path}"


def test_a_cookie_load_error_inside_the_image_does_not_name_the_containers_private_copy() -> None:
    from src.engine.media import _map_error
    from yt_dlp.cookies import CookieLoadError

    err = _map_error(CookieLoadError("failed to load cookies"), in_image=True, cookies=Path("/tmp/anydm-cookies-x.txt"))
    assert str(err) == "cookies file could not be parsed"


async def test_a_cookie_load_error_from_yt_dlp_names_the_users_file(fake, tmp_path: Path, cookie_file: Path) -> None:
    from yt_dlp.cookies import CookieLoadError

    fake.error = CookieLoadError("failed to load cookies")
    with pytest.raises(DownloadError) as e:
        await MediaEngine().run(job(tmp_path, cookies=cookie_file), Collect())
    assert str(e.value) == f"cookies file could not be parsed: {cookie_file}"


def test_an_error_that_is_not_about_a_login_gets_no_hint() -> None:
    from src.engine.media import _map_error

    err = _map_error(yt_dlp.utils.DownloadError("ERROR: Unsupported URL"), cookies=Path("c.txt"))
    assert str(err) == "Unsupported URL"


async def test_a_login_error_from_yt_dlp_gets_the_hint_matching_the_job(fake, tmp_path: Path, cookie_file: Path) -> None:
    fake.error = yt_dlp.utils.DownloadError(LOGIN_MSG)
    with pytest.raises(DownloadError, match=r"pass --cookies FILE"):
        await MediaEngine().run(job(tmp_path), Collect())
    with pytest.raises(DownloadError, match="may be expired"):
        await MediaEngine().run(job(tmp_path, cookies=cookie_file), Collect())
