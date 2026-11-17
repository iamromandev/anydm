"""Queue with media rows in containers, against the fake docker."""

import asyncio
import time
from collections.abc import Callable
from pathlib import Path

import pytest
from src import engine
from src.config.settings import Settings
from src.engine import Kind, resolve
from src.tui.app import AddScreen, AnydmApp
from src.tui.queue import Queue, State
from textual.widgets import Input

from tests.conftest import FakeDocker

HELLO = {"type": "hello", "protocol": 3}
URL = "https://www.youtube.com/watch?v="


@pytest.fixture
def docker(monkeypatch: pytest.MonkeyPatch, fake_docker: FakeDocker) -> FakeDocker:
    monkeypatch.setattr(engine, "_has_local_yt_dlp", lambda: False)
    fake_docker.set(check=True, lines=[HELLO], hang=True)
    return fake_docker


def make(tmp_path: Path) -> Queue:
    return Queue(Settings(download_dir=tmp_path / "dl"), 3, lambda: None)


async def add(q: Queue, url: str):
    """What the add dialog does: classify on a worker thread (this asks docker), then add on the loop."""
    await asyncio.to_thread(resolve, url, None)
    return q.add(url, None)


def downloads(docker: FakeDocker) -> list[list[str]]:
    return [c for c in docker.calls() if c[0] == "run" and "download" in c]


def name_of(call: list[str]) -> str:
    return call[call.index("--name") + 1]


async def until(cond: Callable[[], object], timeout: float = 10) -> None:
    async with asyncio.timeout(timeout):
        while not cond():
            await asyncio.sleep(0.01)


async def test_cancelling_a_media_row_stops_its_container(docker: FakeDocker, tmp_path: Path) -> None:
    q = make(tmp_path)
    item = await add(q, URL + "a")
    await until(lambda: len(downloads(docker)) == 1)
    name = name_of(downloads(docker)[0])
    q.cancel(item.id)
    await until(lambda: item.state is State.CANCELLED)
    assert ["kill", "--signal", "INT", name] in docker.calls()


async def test_retry_starts_a_new_container(docker: FakeDocker, tmp_path: Path) -> None:
    q = make(tmp_path)
    item = await add(q, URL + "a")
    await until(lambda: len(downloads(docker)) == 1)
    q.cancel(item.id)
    await until(lambda: item.state is State.CANCELLED)
    q.retry(item.id)
    await until(lambda: len(downloads(docker)) == 2)
    first, second = (name_of(c) for c in downloads(docker))
    assert first != second
    await q.shutdown()


async def test_quitting_stops_both_running_containers_within_2_seconds(docker: FakeDocker, tmp_path: Path) -> None:
    q = make(tmp_path)
    a, b = await add(q, URL + "a"), await add(q, URL + "b")
    await until(lambda: len(downloads(docker)) == 2)
    await asyncio.sleep(0.2)
    start = time.monotonic()
    await q.shutdown()
    assert time.monotonic() - start < 2
    assert a.state is State.CANCELLED and b.state is State.CANCELLED
    names = {name_of(c) for c in downloads(docker)}
    killed = {c[-1] for c in docker.calls() if c[0] == "kill"}
    assert killed == names


async def test_adding_a_row_never_runs_docker_on_the_loop(docker: FakeDocker, tmp_path: Path) -> None:
    q = make(tmp_path)
    item = q.add(URL + "never-asked", None)  # nobody resolved it on a thread: only the cache is read
    assert item.route.kind is Kind.HTTP
    assert "run" not in docker.commands() and "version" not in docker.commands()
    await q.shutdown()


async def test_a_notice_from_classification_lands_on_the_row_and_is_drained(
    docker: FakeDocker, tmp_path: Path
) -> None:
    from src import engine

    docker.set(version="fail")
    q = make(tmp_path)
    item = await add(q, "https://example.com/a.bin")
    assert item.route.kind is Kind.HTTP
    assert item.notice is not None and "media detection skipped" in item.notice
    assert engine.take_notices() == []
    await q.shutdown()


async def test_quitting_finishes_the_container_cleanup_even_when_the_container_ignores_the_kill(
    docker: FakeDocker, tmp_path: Path
) -> None:
    docker.set(ignore_kill=True)
    q = make(tmp_path)
    item = await add(q, URL + "a")
    await until(lambda: len(downloads(docker)) == 1)
    name = name_of(downloads(docker)[0])
    await asyncio.sleep(0.2)
    start = time.monotonic()
    await q.shutdown()
    assert time.monotonic() - start < 2
    assert item.state is State.CANCELLED
    assert docker.marked("removed") == [name]  # the 2 s quit limit did not cut the cleanup off


async def test_the_add_dialog_shows_a_protocol_mismatch_instead_of_crashing(
    docker: FakeDocker, tmp_path: Path
) -> None:
    docker.set(check_lines=[{"type": "hello", "protocol": 99}])
    app = AnydmApp(Settings(download_dir=tmp_path / "dl"), 3)
    async with app.run_test(size=(100, 30)) as pilot:
        await pilot.press("a")
        await pilot.pause()
        pilot.app.screen.query_one("#link", Input).value = "https://example.com/a.bin"
        await pilot.press("enter")
        await pilot.pause(0.5)
        assert isinstance(app.screen, AddScreen)
        assert "update the media image" in str(app.screen.query_one("#add-error").render())
        assert app.queue.items == []
