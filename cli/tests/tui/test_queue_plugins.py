"""The queue and the add dialog with installed plugins, against the fake docker."""

import asyncio
from collections.abc import Callable
from pathlib import Path

import pytest
from src import engine
from src.config.settings import Settings
from src.engine import Kind, Route, resolve
from src.plugin import state
from src.plugin.model import Plugin
from src.tui.app import AddScreen, AnydmApp, render_row
from src.tui.queue import Queue, State
from textual.widgets import DataTable, Input

from tests.conftest import FakeDocker

HELLO = {"type": "hello", "protocol": 3}
A_IMAGE = "ghcr.io/acme/a@sha256:" + "a" * 64
B_IMAGE = "ghcr.io/acme/b@sha256:" + "b" * 64
URL = "https://example.org/thing/"
ALL = frozenset({"check", "download", "info"})


@pytest.fixture
def docker(monkeypatch: pytest.MonkeyPatch, fake_docker: FakeDocker) -> FakeDocker:
    """alpha says no, beta says yes; both downloads hang until cancelled."""
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)
    state.save([Plugin("alpha", A_IMAGE, "1", ALL, frozenset({"network"})),
                Plugin("beta", B_IMAGE, "1", ALL, frozenset({"network", "cookies"}))])
    fake_docker.set(plugins={
        A_IMAGE: {"check": False, "lines": [HELLO], "hang": True},
        B_IMAGE: {"check": True, "lines": [HELLO], "hang": True},
    })
    return fake_docker


def make(tmp_path: Path, **settings: object) -> Queue:
    return Queue(Settings(download_dir=tmp_path / "dl", **settings), 3, lambda: None)  # ty: ignore[invalid-argument-type]


async def add(q: Queue, url: str, plugin: str | None = None):
    """What the add dialog does: resolve on a worker thread (this may ask docker), then add on the loop."""
    await asyncio.to_thread(resolve, url, None, plugin=plugin)
    return q.add(url, None, plugin)


def downloads(docker: FakeDocker) -> list[list[str]]:
    return [c for c in docker.calls() if c[0] == "run" and "download" in c[c.index("--") + 1:]]


def name_of(call: list[str]) -> str:
    return call[call.index("--name") + 1]


async def until(cond: Callable[[], object], timeout: float = 10) -> None:
    async with asyncio.timeout(timeout):
        while not cond():
            await asyncio.sleep(0.01)


async def test_a_row_routed_to_a_plugin_shows_its_name(docker: FakeDocker, tmp_path: Path) -> None:
    q = make(tmp_path)
    item = await add(q, URL + "1")
    assert item.route == Route(Kind.PLUGIN, "beta")
    assert render_row(item)[1] == "beta"
    await until(lambda: len(downloads(docker)) == 1)
    assert downloads(docker)[0][downloads(docker)[0].index("--") + 1] == B_IMAGE
    await q.shutdown()


async def test_a_row_forced_to_a_plugin_shows_its_name_and_asks_nobody(docker: FakeDocker, tmp_path: Path) -> None:
    q = make(tmp_path)
    item = await add(q, URL + "1", "alpha")
    assert render_row(item)[1] == "alpha"
    await until(lambda: len(downloads(docker)) == 1)
    assert [c for c in docker.calls() if c[0] == "run" and "check" in c[c.index("--") + 1:]] == []
    await q.shutdown()


async def test_cancelling_a_plugin_row_removes_that_plugins_container(docker: FakeDocker, tmp_path: Path) -> None:
    q = make(tmp_path)
    item = await add(q, URL + "1")
    await until(lambda: len(downloads(docker)) == 1)
    name = name_of(downloads(docker)[0])
    assert name.startswith("anydm-beta-")
    q.cancel(item.id)
    await until(lambda: item.state is State.CANCELLED)
    assert ["kill", "--signal", "INT", name] in docker.calls()
    assert name in docker.marked("removed")  # the cleanup finishes with `rm -f`, not only the signal


async def test_adding_a_row_for_an_unknown_plugin_is_a_usage_error(docker: FakeDocker, tmp_path: Path) -> None:
    from src.core.errors import UsageError

    q = make(tmp_path)
    with pytest.raises(UsageError, match="no plugin named"):
        q.add(URL + "1", None, "nope")
    assert q.items == []


COOKIES = "# Netscape HTTP Cookie File\n.example.org\tTRUE\t/\tFALSE\t2000000000\tsid\tvalue\n"


async def test_cookies_reach_only_a_plugin_with_the_permission(docker: FakeDocker, tmp_path: Path) -> None:
    cookies = tmp_path / "c.txt"
    cookies.write_text(COOKIES)
    cookies.chmod(0o600)
    q = make(tmp_path, cookies_file=cookies)
    a = await add(q, URL + "1", "alpha")
    b = await add(q, URL + "2", "beta")
    await until(lambda: len(downloads(docker)) == 2)
    by_image = {c[c.index("--") + 1]: c for c in downloads(docker)}
    assert "--mount" not in by_image[A_IMAGE] and "--cookies" not in by_image[A_IMAGE]
    assert "--mount" in by_image[B_IMAGE] and by_image[B_IMAGE][-1] == "--cookies"
    await q.shutdown()
    assert a.state is State.CANCELLED and b.state is State.CANCELLED


async def test_a_row_for_a_plugin_without_the_permission_says_the_cookies_were_not_passed(
    docker: FakeDocker, tmp_path: Path
) -> None:
    cookies = tmp_path / "c.txt"
    cookies.write_text(COOKIES)
    cookies.chmod(0o600)
    q = make(tmp_path, cookies_file=cookies)
    a = await add(q, URL + "1", "alpha")
    b = await add(q, URL + "2", "beta")
    await until(lambda: len(downloads(docker)) == 2)
    assert a.notice is not None and "cookies were not passed to plugin alpha" in a.notice
    assert b.notice is None
    await q.shutdown()


async def test_the_state_file_is_read_once_for_many_rows(
    docker: FakeDocker, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import tomllib

    parses: list[int] = []
    real = tomllib.loads
    monkeypatch.setattr(tomllib, "loads", lambda text, **kw: (parses.append(1), real(text, **kw))[1])
    cookies = tmp_path / "c.txt"
    cookies.write_text(COOKIES)
    cookies.chmod(0o600)
    q = make(tmp_path, cookies_file=cookies)
    rows = [await add(q, URL + str(n), "beta") for n in range(3)]
    await until(lambda: len(downloads(docker)) == 3)
    assert len(parses) == 1
    await q.shutdown()
    assert all(r.state is State.CANCELLED for r in rows)


async def test_a_bad_cookies_file_fails_only_rows_that_would_read_it(docker: FakeDocker, tmp_path: Path) -> None:
    bad = tmp_path / "bad.txt"
    bad.write_text("not cookies\n")
    q = make(tmp_path, cookies_file=bad)
    a = await add(q, URL + "1", "alpha")
    b = await add(q, URL + "2", "beta")
    await until(lambda: b.state is State.FAILED)
    assert b.error is not None and "not a Netscape cookies file" in b.error
    await until(lambda: len(downloads(docker)) == 1)
    assert a.state is State.RUNNING
    await q.shutdown()


async def test_the_add_dialog_takes_an_optional_plugin(docker: FakeDocker, tmp_path: Path) -> None:
    app = AnydmApp(Settings(download_dir=tmp_path / "dl"), 3)
    async with app.run_test(size=(100, 30)) as pilot:
        await pilot.press("a")
        await pilot.pause()
        assert isinstance(app.screen, AddScreen)
        app.screen.query_one("#link", Input).value = URL + "1"
        app.screen.query_one("#plugin", Input).value = "alpha"
        await pilot.press("enter")
        await pilot.pause(0.5)
        [item] = app.queue.items
        assert item.route == Route(Kind.PLUGIN, "alpha")
        table = app.query_one(DataTable)
        assert [str(c) for c in table.get_row_at(0)][1] == "alpha"
        assert [c for c in docker.calls() if c[0] == "run" and "check" in c[c.index("--") + 1:]] == []
        await app.queue.shutdown()


async def test_the_add_dialog_without_a_plugin_routes_by_check(docker: FakeDocker, tmp_path: Path) -> None:
    app = AnydmApp(Settings(download_dir=tmp_path / "dl"), 3)
    async with app.run_test(size=(100, 30)) as pilot:
        await pilot.press("a")
        await pilot.pause()
        app.screen.query_one("#link", Input).value = URL + "1"
        await pilot.press("enter")
        await pilot.pause(0.8)
        [item] = app.queue.items
        assert item.route == Route(Kind.PLUGIN, "beta")
        await app.queue.shutdown()


@pytest.mark.parametrize("plugin,message", [("nope", "no plugin named"), ("Alpha", "no plugin named")])
async def test_the_add_dialog_shows_an_unknown_plugin(docker: FakeDocker, tmp_path: Path, plugin: str, message: str) -> None:
    app = AnydmApp(Settings(download_dir=tmp_path / "dl"), 3)
    async with app.run_test(size=(100, 30)) as pilot:
        await pilot.press("a")
        await pilot.pause()
        app.screen.query_one("#link", Input).value = URL + "1"
        app.screen.query_one("#plugin", Input).value = plugin
        await pilot.press("enter")
        await pilot.pause(0.5)
        assert isinstance(app.screen, AddScreen)
        assert message in str(app.screen.query_one("#add-error").render())
        assert app.queue.items == []


async def test_the_add_dialog_shows_docker_missing_for_a_forced_plugin(docker: FakeDocker, tmp_path: Path) -> None:
    docker.set(version="fail")
    app = AnydmApp(Settings(download_dir=tmp_path / "dl"), 3)
    async with app.run_test(size=(100, 30)) as pilot:
        await pilot.press("a")
        await pilot.pause()
        app.screen.query_one("#link", Input).value = URL + "1"
        app.screen.query_one("#plugin", Input).value = "alpha"
        await pilot.press("enter")
        await pilot.pause(0.5)
        assert "anydm doctor" in str(app.screen.query_one("#add-error").render())
        assert app.queue.items == []
