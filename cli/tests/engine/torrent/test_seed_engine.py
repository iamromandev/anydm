"""`--seed` in the engine: the listening port, a clean and quick shutdown, and what is announced when."""

import asyncio
import socket
import time
from pathlib import Path

import pytest
from src.engine import Kind, engine_for
from src.engine import torrent as engine_mod
from src.engine.torrent.seed import Seeder

from tests.engine.torrent.seeding import stalled_peer
from tests.engine.torrent.test_engine import Notices, job, leech, seeding_query, setup_swarm
from tests.engine.torrent.trackers import HttpTracker


@pytest.fixture
def tracker():
    t = HttpTracker([]).start()
    yield t
    t.stop()


async def start_seeding(tmp_path: Path, tracker: HttpTracker, **kw):
    """Run `--seed` on a torrent from a local swarm until it announces the seeding phase.
    Returns (swarm, the running task, the port it listens on, its folder)."""
    swarm, f = await setup_swarm(tmp_path, tracker, **kw)
    dest = tmp_path / "dl"
    dest.mkdir()
    task = asyncio.create_task(engine_for(Kind.TORRENT).run(job(str(f), dest, seed=True), Notices()))
    port = int((await seeding_query(tracker))["port"][0])
    return swarm, task, port, dest


# --- the port ---


async def test_the_download_phase_announces_no_port_because_nothing_listens(
    tmp_path: Path, tracker: HttpTracker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(engine_mod, "LISTEN_PORT", 54321)  # not listening: without --seed nobody ever does
    swarm, f = await setup_swarm(tmp_path, tracker)
    try:
        (tmp_path / "dl").mkdir()
        await engine_for(Kind.TORRENT).run(job(str(f), tmp_path / "dl"), Notices())
        assert tracker.queries and {q["port"][0] for q in tracker.queries} == {"0"}
    finally:
        await swarm.seeder.stop()


async def test_when_the_usual_port_is_taken_seeding_uses_another_one(
    tmp_path: Path, tracker: HttpTracker, monkeypatch: pytest.MonkeyPatch
) -> None:
    blocker = socket.socket()
    blocker.bind(("0.0.0.0", 0))
    blocker.listen()
    taken = blocker.getsockname()[1]
    monkeypatch.setattr(engine_mod, "LISTEN_PORT", taken)
    swarm, task, port, _ = await start_seeding(tmp_path, tracker)
    try:
        assert port not in (0, taken)
        assert await leech(port, swarm) == swarm.payload[: swarm.torrent.piece_size(0)]
        task.cancel()
        await asyncio.wait_for(task, 5)
    finally:
        task.cancel()
        blocker.close()
        await swarm.seeder.stop()


# --- Ctrl-C ---


async def test_cancelling_while_the_listener_starts_still_returns_the_paths(
    tmp_path: Path, tracker: HttpTracker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(engine_mod, "LISTEN_PORT", 0)
    started = asyncio.Event()

    async def hang(self: Seeder) -> None:
        started.set()
        await asyncio.sleep(60)

    swarm, f = await setup_swarm(tmp_path, tracker)
    monkeypatch.setattr(Seeder, "start", hang)  # after the swarm's own seeder is up
    (tmp_path / "dl").mkdir()
    task = asyncio.create_task(engine_for(Kind.TORRENT).run(job(str(f), tmp_path / "dl", seed=True), Notices()))
    try:
        await asyncio.wait_for(started.wait(), 10)
        task.cancel()
        paths = await asyncio.wait_for(task, 5)
        assert len(paths) == 3
        assert not any(q.get("event") == ["started"] and q.get("left") == ["0"] for q in tracker.queries)  # no seeding announce
    finally:
        task.cancel()
        await swarm.seeder.stop()


async def test_a_second_cancel_is_not_swallowed(tmp_path: Path, tracker: HttpTracker, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(engine_mod, "LISTEN_PORT", 0)
    swarm, task, _, _ = await start_seeding(tmp_path, tracker)
    try:
        task.cancel()
        task.cancel()  # two requests: the owner of the second still expects to see it
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 5)
    finally:
        task.cancel()
        await swarm.seeder.stop()


async def test_shutdown_is_quick_with_a_dead_tracker_and_a_peer_that_does_not_read(
    tmp_path: Path, tracker: HttpTracker, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(engine_mod, "LISTEN_PORT", 0)
    swarm, task, port, dest = await start_seeding(tmp_path, tracker)
    try:
        tracker.stall = 30.0  # from now on the tracker never answers in time
        _, w = await stalled_peer(swarm, requests=200, port=port)  # asks our seeder for blocks and never reads them
        started = time.monotonic()
        task.cancel()
        paths = await asyncio.wait_for(task, 5)
        assert time.monotonic() - started < 1.0  # the `stopped` announce is capped at 0.8 s and runs beside the shutdown
        assert len(paths) == 3 and paths[0].is_relative_to(dest.resolve())
        w.close()
    finally:
        tracker.stall = 0.0
        task.cancel()
        await swarm.seeder.stop()
