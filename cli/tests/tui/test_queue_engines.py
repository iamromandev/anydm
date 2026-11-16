"""Queue with the real engines against local servers: no internet."""

import asyncio
import hashlib
import os
import random
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest
import yt_dlp
from src.config.settings import Settings
from src.engine import Kind
from src.tui import queue as queue_module
from src.tui.queue import Item, Queue, State
from yt_dlp.globals import extractors as extractor_registry

from tests.engine.test_media_real import FixtureIE, _Handler
from tests.engine.torrent.cli_support import Live

SIZE = 3 << 20


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def make(tmp_path: Path, on_change: Callable[[], None] = lambda: None) -> Queue:
    return Queue(Settings(download_dir=tmp_path / "dl"), 3, on_change)


async def until(cond: Callable[[], object], timeout: float = 20) -> None:
    async with asyncio.timeout(timeout):
        while not cond():
            await asyncio.sleep(0.005)


async def test_http_goes_queued_running_done_and_creates_the_folder(serve, tmp_path: Path) -> None:
    data = os.urandom(SIZE)
    srv = serve(data, disposition='attachment; filename="server-name.bin"')
    seen: list[State] = []
    q = make(tmp_path, lambda: seen.append(q.items[0].state) if q.items else None)
    dest = tmp_path / "new" / "folder"
    item = q.add(srv.url(), dest)
    assert item.state is State.RUNNING or item.state is State.QUEUED
    await until(lambda: item.state is State.DONE)
    assert State.RUNNING in seen
    assert item.progress is not None
    assert item.progress.name == "server-name.bin"
    [path] = item.paths
    assert path == (dest / "server-name.bin").resolve()
    assert sha(path) == hashlib.sha256(data).hexdigest()


async def test_cancel_at_half_keeps_partial_state_and_retry_fetches_only_the_rest(serve, tmp_path: Path) -> None:
    data = os.urandom(SIZE)
    srv = serve(data, delay=0.01)
    q = make(tmp_path)
    dest = tmp_path / "out"
    item = q.add(srv.url(), dest)
    await until(lambda: srv.sent >= SIZE // 2)
    q.cancel(item.id)
    await until(lambda: item.state is State.CANCELLED)
    assert any(p.name.startswith("file.bin.part") for p in dest.iterdir())
    assert not (dest / "file.bin").exists()
    srv.delay = 0
    before = srv.sent
    q.retry(item.id)
    await until(lambda: item.state is State.DONE)
    assert srv.sent - before < SIZE
    assert sha(item.paths[0]) == hashlib.sha256(data).hexdigest()
    q.remove(item.id)
    assert q.items == []
    assert (dest / "file.bin").exists()


async def test_404_fails_with_http_404_and_retry_queues_again(serve, tmp_path: Path) -> None:
    srv = serve(b"x", missing={"/gone.bin"})
    q = make(tmp_path)
    item = q.add(srv.url("/gone.bin"), None)
    await until(lambda: item.state is State.FAILED)
    assert item.error is not None
    assert "HTTP 404" in item.error
    q.retry(item.id)
    assert item.state is State.RUNNING
    await until(lambda: item.state is State.FAILED)


async def test_same_link_twice_fails_the_second_as_already_in_progress(serve, tmp_path: Path) -> None:
    srv = serve(os.urandom(SIZE), delay=0.01)
    q = make(tmp_path)
    first = q.add(srv.url(), tmp_path / "o")
    await until(lambda: srv.sent > 0)
    second = q.add(srv.url(), tmp_path / "o")
    await until(lambda: second.state is State.FAILED)
    assert second.error is not None
    assert "already in progress" in second.error
    await q.shutdown()
    assert first.state is State.CANCELLED


async def test_unwritable_folder_fails_before_any_request(serve, tmp_path: Path) -> None:
    srv = serve(b"x")
    blocker = tmp_path / "file"
    blocker.write_text("x")
    q = make(tmp_path)
    item = q.add(srv.url(), blocker / "sub")
    await until(lambda: item.state is State.FAILED)
    assert srv.log == []


async def test_random_cancels_then_retry_match_and_leave_nothing_behind(serve, tmp_path: Path) -> None:
    data = os.urandom(SIZE)
    want = hashlib.sha256(data).hexdigest()
    srv = serve(data, delay=0.01)
    rng = random.Random(7)
    for i in range(10):
        q = make(tmp_path)
        dest = tmp_path / f"run{i}"
        srv.sent = 0
        srv.delay = 0.01
        item = q.add(srv.url(), dest)
        target = rng.uniform(0.02, 0.9) * SIZE
        await until(lambda item=item, target=target: srv.sent >= target or item.state is State.DONE)
        await q.shutdown()
        assert item.state in (State.CANCELLED, State.DONE)
        assert asyncio.all_tasks() == {asyncio.current_task()}
        srv.delay = 0
        q.retry(item.id)
        await until(lambda item=item: item.state is State.DONE)
        assert sha(item.paths[0]) == want
        assert not list(dest.glob("*.lock"))
        assert all(not n.name.endswith((".part", ".json", ".lock")) for n in dest.iterdir())


@pytest.fixture
def live(tmp_path: Path) -> Iterator:
    made: list[Live] = []

    def start(files: dict[str, bytes]) -> Live:
        lv = Live(tmp_path / f"seed{len(made)}", files, 0.0)
        made.append(lv)
        return lv

    yield start
    for lv in made:
        lv.stop()


@pytest.mark.torrent
async def test_local_torrent_shows_phase_and_peers_finishes_and_does_not_seed(live, tmp_path: Path) -> None:
    files = {"a.bin": os.urandom(300_000), "b.bin": os.urandom(200_000)}
    lv = live(files)
    torrent = tmp_path / "t.torrent"
    torrent.write_bytes(lv.swarm.raw)
    phases: set[str] = set()
    peers: list[int] = []

    def watch() -> None:
        progress = q.items[0].progress if q.items else None
        if progress is not None:
            if progress.phase:
                phases.add(progress.phase)
            if progress.peers is not None:
                peers.append(progress.peers)

    q = make(tmp_path, watch)
    item: Item = q.add(str(torrent), tmp_path / "out")
    assert item.kind is Kind.TORRENT
    await until(lambda: item.state in (State.DONE, State.FAILED), 60)
    assert item.state is State.DONE, item.error
    assert phases
    assert peers
    assert {p.name: sha(p) for p in item.paths} == {n: hashlib.sha256(d).hexdigest() for n, d in files.items()}
    assert not q.busy
    assert asyncio.all_tasks() == {asyncio.current_task()}


@pytest.fixture
def media_fixture(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    import threading
    from http.server import ThreadingHTTPServer

    srv = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    FixtureIE.base = f"http://127.0.0.1:{srv.server_port}"
    monkeypatch.setitem(extractor_registry.value, "FixtureIE", FixtureIE)
    real_ydl = yt_dlp.YoutubeDL

    class YDL(real_ydl):
        def __init__(self, params: dict[str, Any] | None = None, *a: Any, **kw: Any) -> None:
            super().__init__(params, *a, **kw)
            self._ies = {"Fixture": FixtureIE, **self._ies}

    monkeypatch.setattr(yt_dlp, "YoutubeDL", YDL)
    monkeypatch.setattr(queue_module, "resolve", lambda source, forced, **kw: Kind.MEDIA)  # the fixture is not a URL
    yield
    srv.shutdown()


@pytest.mark.usefixtures("media_fixture")
async def test_media_playlist_downloads_one_item(tmp_path: Path) -> None:
    q = make(tmp_path)
    item = q.add("fixture:list", tmp_path / "m")
    await until(lambda: item.state in (State.DONE, State.FAILED), 30)
    assert item.state is State.DONE, item.error
    assert len(item.paths) == 1
