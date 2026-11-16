import asyncio
import hashlib
import os
import threading
import time
from pathlib import Path

import pytest
from src.config.settings import Settings
from src.core.progress import Progress, Reporter
from src.engine import Job
from src.tui import system
from src.tui.app import AddScreen, AnydmApp, ConfirmQuit
from src.tui.queue import State
from textual.widgets import DataTable
from textual.widgets._footer import FooterKey


class Fake:
    """An engine that reports a hostile name and waits for its gate."""

    def __init__(self, name: str = "n") -> None:
        self.name = name
        self.gate = asyncio.Event()

    async def run(self, job: Job, report: Reporter) -> list[Path]:
        report.update(Progress(10, 100, 5.0, name=self.name))
        await self.gate.wait()
        return [job.dest_dir / "f.bin"]


def make_app(tmp_path: Path, fake: Fake | None = None, max_active: int = 3) -> AnydmApp:
    fake = fake or Fake()
    return AnydmApp(Settings(download_dir=tmp_path / "dl"), max_active, lambda kind: fake)


def cells(app: AnydmApp) -> list[list[str]]:
    table = app.query_one(DataTable)
    return [[str(c) for c in table.get_row_at(i)] for i in range(table.row_count)]


async def type_link(pilot, link: str, folder: Path | None = None) -> None:
    await pilot.press("a")
    await pilot.pause()
    pilot.app.screen.query_one("#link").value = link
    if folder is not None:
        pilot.app.screen.query_one("#folder").value = str(folder)
    await pilot.press("enter")
    await pilot.pause()


async def until(pilot, cond, timeout: float = 15) -> None:
    deadline = time.monotonic() + timeout
    while not cond():
        assert time.monotonic() < deadline, "timed out"
        await pilot.pause(0.05)


async def test_add_dialog_downloads_a_real_file_and_creates_the_folder(serve, tmp_path: Path) -> None:
    from src.engine import engine_for

    data = os.urandom(1 << 20)
    srv = serve(data, disposition='attachment; filename="served.bin"')
    app = AnydmApp(Settings(download_dir=tmp_path / "dl"), 3, engine_for)
    dest = tmp_path / "does" / "not" / "exist"
    async with app.run_test(size=(100, 30)) as pilot:
        await type_link(pilot, srv.url(), dest)
        assert isinstance(app.screen, AddScreen) is False
        await until(pilot, lambda: app.queue.items and app.queue.items[0].state is State.DONE)
        await pilot.pause(0.4)
        assert cells(app)[0][0] == "served.bin"
        assert cells(app)[0][2] == "done"
    assert hashlib.sha256((dest / "served.bin").read_bytes()).hexdigest() == hashlib.sha256(data).hexdigest()


async def test_invalid_link_shows_an_error_and_adds_no_row(tmp_path: Path) -> None:
    app = make_app(tmp_path)
    async with app.run_test(size=(100, 30)) as pilot:
        await type_link(pilot, "ftp://x")
        assert isinstance(app.screen, AddScreen)
        assert str(app.screen.query_one("#add-error").render()) != ""
        assert app.queue.items == []


async def test_footer_lists_every_key(tmp_path: Path) -> None:
    app = make_app(tmp_path)
    async with app.run_test(size=(120, 30)) as pilot:
        await pilot.pause()
        keys = {k.key_display or k.key for k in app.query(FooterKey)}
        assert {"a", "c", "r", "d", "o", "y", "q"} <= keys
        assert {"↑", "↓"} <= keys, " ".join(sorted(keys))


async def test_cancel_retry_remove_keys(tmp_path: Path) -> None:
    fake = Fake()
    app = make_app(tmp_path, fake)
    async with app.run_test(size=(100, 30)) as pilot:
        app.queue.add("http://h/a", None)
        await pilot.pause(0.3)
        assert cells(app)[0][2] == "running"
        await pilot.press("c")
        await until(pilot, lambda: cells(app)[0][2] == "cancelled")
        await pilot.press("r")
        assert cells(app)[0][2] in ("queued", "running")
        await pilot.press("c")
        await until(pilot, lambda: cells(app)[0][2] == "cancelled")
        await pilot.press("d")
        assert cells(app) == []


async def test_down_and_up_move_the_selection(tmp_path: Path) -> None:
    app = make_app(tmp_path)
    async with app.run_test(size=(100, 30)) as pilot:
        app.queue.add("http://h/a", None)
        app.queue.add("http://h/b", None)
        await pilot.pause(0.3)
        await pilot.press("down")
        assert app.query_one(DataTable).cursor_row == 1
        await pilot.press("up")
        assert app.query_one(DataTable).cursor_row == 0


async def test_open_and_copy_use_the_folder_and_the_absolute_path(tmp_path: Path, monkeypatch) -> None:
    opened: list[Path] = []
    copied: list[Path] = []
    monkeypatch.setattr(system, "open_folder", lambda path, app: opened.append(path))
    monkeypatch.setattr(system, "copy_path", lambda path, app: copied.append(path))
    fake = Fake()
    fake.gate.set()
    app = make_app(tmp_path, fake)
    async with app.run_test(size=(100, 30)) as pilot:
        item = app.queue.add("http://h/a", None)
        await until(pilot, lambda: item.state is State.DONE)
        await pilot.pause(0.3)
        await pilot.press("o")
        await pilot.press("y")
    folder = (tmp_path / "dl").resolve()
    assert opened == [folder]
    assert copied == [folder / "f.bin"]


async def test_open_and_copy_do_nothing_on_a_row_that_is_not_done(tmp_path: Path, monkeypatch) -> None:
    calls: list[object] = []
    monkeypatch.setattr(system, "open_folder", lambda path, app: calls.append(path))
    monkeypatch.setattr(system, "copy_path", lambda path, app: calls.append(path))
    app = make_app(tmp_path)
    async with app.run_test(size=(100, 30)) as pilot:
        app.queue.add("http://h/a", None)
        await pilot.pause(0.3)
        await pilot.press("o", "y")
    assert calls == []


async def test_q_with_nothing_running_exits_at_once(tmp_path: Path) -> None:
    app = make_app(tmp_path)
    async with app.run_test(size=(100, 30)) as pilot:
        await pilot.press("q")
        await pilot.pause()
    assert app.return_code == 0


async def test_q_with_running_downloads_confirms_then_exits_within_2s(tmp_path: Path) -> None:
    app = make_app(tmp_path)
    async with app.run_test(size=(100, 30)) as pilot:
        app.queue.add("http://h/a", None)
        app.queue.add("http://h/b", None)
        await pilot.pause(0.3)
        await pilot.press("q")
        await pilot.pause()
        assert isinstance(app.screen, ConfirmQuit)
        start = time.monotonic()
        await pilot.press("y")
        await until(pilot, lambda: app.return_code is not None, 2)
        assert time.monotonic() - start < 2
    assert app.return_code == 0
    assert {i.state for i in app.queue.items} == {State.CANCELLED}


class Stubborn:
    """An engine that ignores cancellation until `release` is set (the test does, so the loop can close)."""

    def __init__(self) -> None:
        self.release = asyncio.Event()

    async def run(self, job: Job, report: Reporter) -> list[Path]:
        while not self.release.is_set():
            try:
                await asyncio.wait_for(self.release.wait(), 3600)
            except asyncio.CancelledError:
                continue
        raise asyncio.CancelledError


async def test_quitting_over_an_engine_that_ignores_cancel_takes_at_most_2s_in_total(tmp_path: Path) -> None:
    engine = Stubborn()
    app = AnydmApp(Settings(download_dir=tmp_path / "dl"), 3, lambda kind: engine)
    start = time.monotonic()
    try:
        async with app.run_test(size=(100, 30)) as pilot:
            app.queue.add("http://h/a", None)
            await pilot.pause(0.3)
            start = time.monotonic()
            await pilot.press("ctrl+c")
            await until(pilot, lambda: app.return_code is not None, 3)
        elapsed = time.monotonic() - start
    finally:
        engine.release.set()
    assert elapsed < 2
    assert app.return_code == 0


async def test_the_sigint_handler_is_removed_when_the_app_ends(tmp_path: Path) -> None:
    import signal

    loop = asyncio.get_running_loop()
    app = make_app(tmp_path)
    async with app.run_test(size=(100, 30)) as pilot:
        await pilot.pause()
    assert loop.remove_signal_handler(signal.SIGINT) is False  # nothing of ours left to remove


def test_a_notice_is_shown_in_the_row_cleaned_and_cut() -> None:
    from pathlib import Path as P

    from src.engine import Kind
    from src.tui.app import render_row
    from src.tui.queue import Item

    item = Item(1, "http://h/a", P("."), Kind.MEDIA, state=State.RUNNING)
    item.notice = "no ffmpeg\x1b[31m\u202e " + "x" * 200
    state = render_row(item)[2]
    assert state.startswith("running")
    assert "no ffmpeg" in state
    assert "\x1b" not in state
    assert "\u202e" not in state
    assert len(state) < 100


async def test_resize_before_compose_does_not_crash(tmp_path: Path) -> None:
    app = make_app(tmp_path)
    app.on_resize()  # no widgets yet


async def test_declining_the_confirmation_keeps_running(tmp_path: Path) -> None:
    app = make_app(tmp_path)
    async with app.run_test(size=(100, 30)) as pilot:
        app.queue.add("http://h/a", None)
        await pilot.pause(0.3)
        await pilot.press("q")
        await pilot.pause()
        await pilot.press("n")
        await pilot.pause()
        assert app.return_code is None
        assert app.queue.items[0].state is State.RUNNING
        await pilot.press("ctrl+c")
        await until(pilot, lambda: app.return_code is not None, 2)
    assert app.queue.items[0].state is State.CANCELLED


async def test_hostile_names_and_credentials_are_not_shown(tmp_path: Path) -> None:
    app = make_app(tmp_path, Fake("evil\x1b[31m‮name\x07"))
    async with app.run_test(size=(100, 30)) as pilot:
        app.queue.add("http://user:pass@h/a", None)
        app.queue.add("http://user:pass@h/b", None)
        await pilot.pause(0.3)
        shown = "".join(c for row in cells(app) for c in row)
        assert "\x1b" not in shown
        assert "‮" not in shown
        assert "\x07" not in shown
        app.queue.items[0].progress = None  # the link is shown until the name is known
        app.queue._on_change()
        await pilot.pause(0.4)
        shown = "".join(c for row in cells(app) for c in row)
        assert "user" not in shown
        assert "pass" not in shown


async def test_too_small_terminal_shows_a_message(tmp_path: Path) -> None:
    app = make_app(tmp_path)
    async with app.run_test(size=(60, 20)) as pilot:
        await pilot.pause()
        assert app.query_one("#toosmall").display is True
        assert app.query_one(DataTable).display is False
    async with make_app(tmp_path).run_test(size=(80, 24)) as pilot:
        await pilot.pause()
        assert pilot.app.query_one("#toosmall").display is False


async def test_first_screen_draws_within_a_second(tmp_path: Path) -> None:
    t = time.perf_counter()
    app = make_app(tmp_path)
    async with app.run_test(size=(100, 30)) as pilot:
        await pilot.pause()
        assert app.query_one(DataTable).is_attached
    assert time.perf_counter() - t < 1


async def test_max_active_is_respected_in_the_table(tmp_path: Path) -> None:
    app = make_app(tmp_path, max_active=2)
    async with app.run_test(size=(100, 30)) as pilot:
        for n in "abc":
            app.queue.add(f"http://h/{n}", None)
        await pilot.pause(0.4)
        assert [r[2] for r in cells(app)] == ["running", "running", "queued"]


class Threaded:
    """An engine that reports every millisecond from its own thread, like yt-dlp's worker."""

    async def run(self, job: Job, report: Reporter) -> list[Path]:
        stop = threading.Event()

        def work() -> None:
            done = 0
            while not stop.is_set():
                done += 1000
                report.update(Progress(done, None, 1e6, name="threaded"))
                time.sleep(0.001)

        threading.Thread(target=work, daemon=True, name="fake-worker").start()
        try:
            await asyncio.sleep(3600)
        finally:
            stop.set()
        return []


async def _until_not_running(item) -> float:
    start = time.perf_counter()
    while item.state is State.RUNNING:
        await asyncio.sleep(0.002)
    return time.perf_counter() - start


async def _stays_responsive(pilot, app: AnydmApp, progressing: int, victim: int) -> None:
    """Rows keep moving, and a key takes effect within 200 ms, while every engine is busy.

    The latency is the time from sending the key to the queue changing, watched from a task of its own:
    pilot.press() also waits for the screen to settle, which says nothing about how fast the app answers."""
    before = cells(app)[progressing]
    await until(pilot, lambda: cells(app)[progressing] != before, 1)  # a progressing row repaints within a second
    await pilot.press(*["down"] * victim)
    item = app.queue.items[victim]
    for _ in range(3):
        await until(pilot, lambda: item.state is State.RUNNING, 5)
        watcher = asyncio.create_task(_until_not_running(item))
        await pilot.press("c")
        assert await watcher < 0.2
        assert cells(app)[victim][2].startswith(("cancelling", "cancelled"))
        await until(pilot, lambda: item.state is State.CANCELLED, 3)
        await pilot.press("r")
        await pilot.pause(0.1)


async def test_key_press_reacts_within_200_ms_and_rows_repaint_under_load(serve, tmp_path: Path) -> None:
    from src.engine import Kind, engine_for

    srv = serve(os.urandom(64 << 20))  # loopback speed for the whole test
    threaded = Threaded()
    app = AnydmApp(
        Settings(download_dir=tmp_path / "dl"),
        4,
        lambda kind: engine_for(kind) if kind is Kind.HTTP else threaded,
    )
    async with app.run_test(size=(100, 30)) as pilot:
        app.queue.add(srv.url(), None)
        app.queue.add("magnet:?xt=urn:btih:" + "a" * 40, None)
        app.queue.add("magnet:?xt=urn:btih:" + "b" * 40, None)
        await pilot.pause(0.5)
        await _stays_responsive(pilot, app, progressing=1, victim=2)
        assert cells(app)[1][3] != ""


@pytest.mark.torrent
async def test_key_press_reacts_within_200_ms_with_a_real_torrent_under_load(serve, tmp_path: Path) -> None:
    from src.engine import engine_for

    from tests.engine.torrent.cli_support import Live

    seeder = Live(tmp_path / "seed", {"big.bin": os.urandom(24 << 20)}, 0.002)
    torrent = tmp_path / "big.torrent"
    torrent.write_bytes(seeder.swarm.raw)
    srv = serve(os.urandom(64 << 20), delay=0.02)  # slow enough to still be running when it is cancelled
    app = AnydmApp(Settings(download_dir=tmp_path / "dl"), 4, engine_for)
    try:
        async with app.run_test(size=(100, 30)) as pilot:
            app.queue.add(str(torrent), tmp_path / "t")
            app.queue.add(srv.url(), tmp_path / "h")
            await pilot.pause(0.5)
            assert cells(app)[0][1] == "torrent"
            await _stays_responsive(pilot, app, progressing=0, victim=1)
            assert cells(app)[0][3] != ""
    finally:
        seeder.stop()
