import asyncio
from pathlib import Path

import pytest
from src.config.settings import Settings
from src.core.errors import DownloadError, PartialDownload, UsageError
from src.core.progress import Progress, Reporter
from src.engine import Job, Kind
from src.tui.queue import ItemReporter, Queue, State


class FakeEngine:
    """Runs until its gate is set; the jobs it started are recorded."""

    def __init__(self) -> None:
        self.started: list[str] = []
        self.jobs: list[Job] = []
        self.gates: dict[str, asyncio.Event] = {}
        self.outcome: dict[str, BaseException] = {}

    def gate(self, source: str) -> asyncio.Event:
        return self.gates.setdefault(source, asyncio.Event())

    async def run(self, job: Job, report: Reporter) -> list[Path]:
        self.started.append(job.source)
        self.jobs.append(job)
        report.update(Progress(done=5, total=10, speed=1.0, name="n\x1b[31m‮ame"))
        await self.gate(job.source).wait()
        if job.source in self.outcome:
            raise self.outcome[job.source]
        return [job.dest_dir / "out.bin"]


def make(tmp_path: Path, max_active: int = 2) -> tuple[Queue, FakeEngine, list[int]]:
    fake = FakeEngine()
    changes: list[int] = []
    q = Queue(
        Settings(download_dir=tmp_path / "dl"),
        max_active,
        lambda: changes.append(1),
        engine_for=lambda kind: fake,
    )
    return q, fake, changes


async def settle() -> None:
    for _ in range(5):
        await asyncio.sleep(0)


def states(q: Queue) -> list[State]:
    return [i.state for i in q.items]


async def test_caps_running_at_max_active_and_starts_next_in_order(tmp_path: Path) -> None:
    q, fake, _ = make(tmp_path)
    for n in "abc":
        q.add(f"http://h/{n}", None)
    await settle()
    assert states(q) == [State.RUNNING, State.RUNNING, State.QUEUED]
    assert fake.started == ["http://h/a", "http://h/b"]
    fake.gate("http://h/b").set()
    await settle()
    assert states(q) == [State.RUNNING, State.DONE, State.RUNNING]
    assert q.items[1].paths == [(tmp_path / "dl" / "out.bin").resolve()]
    fake.gate("http://h/a").set()
    fake.gate("http://h/c").set()
    await settle()
    assert not q.busy


async def test_bad_link_raises_and_adds_no_item(tmp_path: Path) -> None:
    q, _, _ = make(tmp_path)
    with pytest.raises(UsageError):
        q.add("ftp://x", None)
    assert q.items == []


async def test_progress_name_is_cleaned(tmp_path: Path) -> None:
    q, _, changes = make(tmp_path)
    item = q.add("http://h/a", None)
    await settle()
    assert item.progress is not None
    assert item.progress.name == "n[31mame"
    assert changes


async def test_cancel_running_then_retry_runs_again(tmp_path: Path) -> None:
    q, fake, _ = make(tmp_path)
    item = q.add("http://h/a", None)
    await settle()
    q.cancel(item.id)
    assert item.state is State.CANCELLING
    await settle()
    assert item.state is State.CANCELLED
    q.retry(item.id)
    await settle()
    assert item.state is State.RUNNING
    assert fake.started == ["http://h/a", "http://h/a"]


async def test_cancel_queued_item_never_starts(tmp_path: Path) -> None:
    q, fake, _ = make(tmp_path, max_active=1)
    q.add("http://h/a", None)
    b = q.add("http://h/b", None)
    await settle()
    q.cancel(b.id)
    assert b.state is State.CANCELLED
    fake.gate("http://h/a").set()
    await settle()
    assert fake.started == ["http://h/a"]


async def test_remove_only_when_not_running_and_leaves_files(tmp_path: Path) -> None:
    q, fake, _ = make(tmp_path)
    item = q.add("http://h/a", None)
    await settle()
    q.remove(item.id)
    assert q.items == [item]
    fake.gate("http://h/a").set()
    await settle()
    (tmp_path / "dl" / "keep.bin").write_bytes(b"x")
    q.remove(item.id)
    assert q.items == []
    assert (tmp_path / "dl" / "keep.bin").exists()


@pytest.mark.parametrize(
    ("error", "text"),
    [
        (DownloadError("HTTP 404 for http://h/a"), "HTTP 404"),
        (RuntimeError("secret user:pass"), "internal error: RuntimeError"),
    ],
)
async def test_failures_mark_failed_and_start_next(tmp_path: Path, error: BaseException, text: str) -> None:
    q, fake, _ = make(tmp_path, max_active=1)
    fake.outcome["http://h/a"] = error
    a = q.add("http://h/a", None)
    b = q.add("http://h/b", None)
    fake.gate("http://h/a").set()
    await settle()
    assert a.state is State.FAILED
    assert a.error is not None
    assert text in a.error
    assert "secret" not in a.error
    assert b.state is State.RUNNING
    q.retry(a.id)
    assert a.state is State.QUEUED
    assert a.error is None


async def test_partial_download_keeps_paths(tmp_path: Path) -> None:
    q, fake, _ = make(tmp_path)
    fake.outcome["http://h/a"] = PartialDownload("1 of 2 failed", [Path("/x/one")])
    a = q.add("http://h/a", None)
    fake.gate("http://h/a").set()
    await settle()
    assert a.state is State.FAILED
    assert a.paths == [Path("/x/one")]


async def test_unwritable_folder_fails_without_running_engine(tmp_path: Path) -> None:
    q, fake, _ = make(tmp_path)
    blocker = tmp_path / "file"
    blocker.write_text("x")
    a = q.add("http://h/a", blocker / "sub")
    await settle()
    assert a.state is State.FAILED
    assert a.error is not None
    assert "cannot" in a.error
    assert fake.started == []


async def test_shutdown_cancels_everything_and_leaves_no_tasks(tmp_path: Path) -> None:
    q, _, _ = make(tmp_path, max_active=1)
    q.add("http://h/a", None)
    q.add("http://h/b", None)
    await settle()
    await q.shutdown()
    assert set(states(q)) == {State.CANCELLED}
    assert not q.busy
    assert asyncio.all_tasks() == {asyncio.current_task()}


def test_item_reporter_keeps_notice_and_last_name() -> None:
    from src.tui.queue import Item

    item = Item(1, "s", Path("."), Kind.HTTP)
    r = ItemReporter(item, lambda: None)
    r.update(Progress(1, 2, 0.0, name="a.bin"))
    r.update(Progress(2, 2, 0.0))
    r.notice("single\nstream")
    assert item.progress is not None
    assert item.progress.name == "a.bin"
    assert item.notice == "single stream"


class Stubborn:
    """An engine that ignores cancellation until `release` is set, like a yt-dlp thread that is still winding down."""

    def __init__(self, on_cancel: type[BaseException] | None = None) -> None:
        self.release = asyncio.Event()
        self.on_cancel = on_cancel
        self.started: list[str] = []

    async def run(self, job: Job, report: Reporter) -> list[Path]:
        self.started.append(job.source)
        try:
            await asyncio.sleep(3600)
        except asyncio.CancelledError:
            await self.release.wait()
            if self.on_cancel is not None:
                raise self.on_cancel("boom after cancel") from None
            raise
        return []


def make_with(tmp_path: Path, engine: object, max_active: int = 1) -> Queue:
    return Queue(Settings(download_dir=tmp_path / "dl"), max_active, lambda: None, engine_for=lambda kind: engine)  # ty: ignore[invalid-argument-type]


async def test_cancelling_row_keeps_its_slot_until_the_engine_really_stops(tmp_path: Path) -> None:
    engine = Stubborn()
    q = make_with(tmp_path, engine)
    a = q.add("http://h/a", None)
    b = q.add("http://h/b", None)
    await settle()
    q.cancel(a.id)
    await settle()
    assert a.state is State.CANCELLING
    assert b.state is State.QUEUED  # the slot is still held
    assert q.busy
    q.retry(a.id)  # not allowed while it is still stopping
    assert a.state is State.CANCELLING
    engine.release.set()
    await settle()
    assert a.state is State.CANCELLED
    assert b.state is State.RUNNING
    assert engine.started == ["http://h/a", "http://h/b"]


@pytest.mark.parametrize("error", [DownloadError, RuntimeError])
async def test_cancelling_row_ends_cancelled_even_if_the_engine_turns_the_cancel_into_an_error(
    tmp_path: Path, error: type[BaseException]
) -> None:
    engine = Stubborn(error)
    q = make_with(tmp_path, engine)
    a = q.add("http://h/a", None)
    await settle()
    q.cancel(a.id)
    engine.release.set()
    await settle()
    assert a.state is State.CANCELLED
    assert a.error is None


async def test_cancelling_row_keeps_paths_of_a_partial_result(tmp_path: Path) -> None:
    class Partial(Stubborn):
        async def run(self, job: Job, report: Reporter) -> list[Path]:
            try:
                await asyncio.sleep(3600)
            except asyncio.CancelledError:
                raise PartialDownload("cut short", [Path("/x/one")]) from None
            return []

    q = make_with(tmp_path, Partial())
    a = q.add("http://h/a", None)
    await settle()
    q.cancel(a.id)
    await settle()
    assert a.state is State.CANCELLED
    assert a.paths == [Path("/x/one")]


async def test_progress_from_an_old_run_is_ignored_after_a_retry(tmp_path: Path) -> None:
    reports: list[Reporter] = []

    class Capture:
        async def run(self, job: Job, report: Reporter) -> list[Path]:
            reports.append(report)
            report.update(Progress(1, 10, 1.0, name=f"run{len(reports)}"))
            await asyncio.sleep(3600)
            return []

    q = make_with(tmp_path, Capture())
    item = q.add("http://h/a", None)
    await settle()
    q.cancel(item.id)
    await settle()
    q.retry(item.id)
    await settle()
    assert len(reports) == 2
    reports[1].update(Progress(5, 10, 1.0, name="new"))
    reports[0].update(Progress(9, 10, 1.0, name="stale"))
    reports[0].notice("stale notice")
    assert item.progress is not None
    assert (item.progress.name, item.progress.done) == ("new", 5)
    assert item.notice is None
    await q.shutdown()


SECRET = "s3cr3t-session-value-7f3a"
MEDIA = "https://media.example/watch?v=abc"


def make_with_cookies(tmp_path: Path, cookies: Path) -> tuple[Queue, FakeEngine]:
    fake = FakeEngine()
    q = Queue(
        Settings(download_dir=tmp_path / "dl", cookies_file=cookies), 3, lambda: None, engine_for=lambda kind: fake
    )
    return q, fake


def cookie_file(folder: Path, text: str | None = None, mode: int = 0o600) -> Path:
    f = folder / "cookies.txt"
    f.write_text(text or f"# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tFALSE\t2000000000\tsid\t{SECRET}\n")
    f.chmod(mode)
    return f


async def test_a_media_row_gets_the_cookies_setting_and_http_and_torrent_rows_do_not(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("src.engine._media_cached", lambda url: url == MEDIA)
    cookies = cookie_file(tmp_path)
    q, fake = make_with_cookies(tmp_path, cookies)
    q.add(MEDIA, None)
    q.add("http://h/a.bin", None)
    q.add("magnet:?xt=urn:btih:" + "a" * 40, None)
    await settle()
    by_source = {j.source: j.cookies for j in fake.jobs}
    assert by_source[MEDIA] == cookies.resolve()
    assert by_source["http://h/a.bin"] is None
    assert by_source["magnet:?xt=urn:btih:" + "a" * 40] is None


async def test_without_the_setting_no_row_gets_cookies(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("src.engine._media_cached", lambda url: True)
    q, fake, _ = make(tmp_path)
    q.add(MEDIA, None)
    await settle()
    assert [j.cookies for j in fake.jobs] == [None]


async def test_a_bad_cookies_file_fails_the_row_with_the_message_and_never_starts_the_engine(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from src.tui.app import render_row

    monkeypatch.setattr("src.engine._media_cached", lambda url: True)
    bad = cookie_file(tmp_path, text=f"{SECRET}: not a cookies file\n")
    q, fake = make_with_cookies(tmp_path, bad)
    item = q.add(MEDIA, None)
    await settle()
    assert item.state is State.FAILED
    assert item.error is not None and "not a Netscape cookies file" in item.error and str(bad.resolve()) in item.error
    assert fake.started == []
    assert SECRET not in " ".join(render_row(item))


async def test_a_missing_cookies_file_fails_the_row(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("src.engine._media_cached", lambda url: True)
    q, fake = make_with_cookies(tmp_path, tmp_path / "gone.txt")
    item = q.add(MEDIA, None)
    await settle()
    assert item.state is State.FAILED
    assert item.error is not None and "gone.txt" in item.error
    assert fake.started == []


async def test_a_cookies_file_others_can_read_shows_the_warning_on_the_row_and_goes_on(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("src.engine._media_cached", lambda url: True)
    q, fake = make_with_cookies(tmp_path, cookie_file(tmp_path, mode=0o644))
    item = q.add(MEDIA, None)
    await settle()
    assert item.state is State.RUNNING
    assert item.notice is not None and "chmod 600" in item.notice
    assert SECRET not in item.notice
    assert len(fake.started) == 1


@pytest.mark.parametrize("source", ["http://h/a.bin", "magnet:?xt=urn:btih:" + "a" * 40])
async def test_a_missing_cookies_file_does_not_fail_an_http_or_torrent_row(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, source: str
) -> None:
    monkeypatch.setattr("src.engine._media_cached", lambda url: False)
    q, fake = make_with_cookies(tmp_path, tmp_path / "gone.txt")
    item = q.add(source, None)
    await settle()
    assert item.state is State.RUNNING
    assert item.error is None and len(fake.started) == 1


async def test_a_readable_by_others_cookies_file_shows_no_warning_on_an_http_row(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("src.engine._media_cached", lambda url: False)
    q, _ = make_with_cookies(tmp_path, cookie_file(tmp_path, mode=0o644))
    item = q.add("http://h/a.bin", None)
    await settle()
    assert item.state is State.RUNNING
    assert item.notice is None
