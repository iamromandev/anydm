"""Shared by the CLI end to end tests: a seeder and a tracker on background threads, and the CLI as a subprocess."""

import asyncio
import hashlib
import os
import subprocess
import sys
import threading
from pathlib import Path
from typing import Any

from tests.engine.torrent.fake_dht import FakeNode, chain
from tests.engine.torrent.seeding import Swarm, make_seeder
from tests.engine.torrent.trackers import HttpTracker

CLI_DIR = Path(__file__).parents[3]


class Live:
    """A seeder serving on its own event loop in a thread, so a subprocess can talk to it."""

    def __init__(
        self, root: Path, files: dict[str, bytes], delay: float, extra_tiers: list[list[str]] | None = None, **kw: Any
    ) -> None:
        self.tracker = HttpTracker([]).start()
        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self.loop.run_forever, daemon=True).start()
        tiers = [*(extra_tiers or []), [self.tracker.url]]
        self.swarm: Swarm = asyncio.run_coroutine_threadsafe(
            make_seeder(root, files, tiers=tiers, delay=delay, **kw), self.loop
        ).result(10)
        self.tracker.peers = [("127.0.0.1", self.swarm.seeder.port)]
        self.dht_nodes: list[FakeNode] = []

    def magnet(self, tracker: bool = True, name: str | None = None) -> str:
        uri = "magnet:?xt=urn:btih:" + self.swarm.torrent.infohash.hex()
        if name:
            uri += "&dn=" + name
        if tracker:
            uri += "&tr=" + self.tracker.url.replace(":", "%3A").replace("/", "%2F")
        return uri

    def start_dht(self, hops: int = 2) -> dict[str, str]:
        """A fake DHT network that knows the seeder only at its far end; the environment that points the CLI at it."""
        self.dht_nodes = asyncio.run_coroutine_threadsafe(
            chain(self.swarm.torrent.infohash, [("127.0.0.1", self.swarm.seeder.port)], hops, decoys=2), self.loop
        ).result(10)
        return {"ANYDM_DHT_BOOTSTRAP": f"127.0.0.1:{self.dht_nodes[0].port}"}

    def stop(self) -> None:
        for n in self.dht_nodes:
            self.loop.call_soon_threadsafe(n.stop)
        asyncio.run_coroutine_threadsafe(self.swarm.seeder.stop(), self.loop).result(10)
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.tracker.stop()


def anydm(*args: str, env: dict[str, str] | None = None) -> subprocess.Popen[str]:
    """The CLI as a subprocess. The DHT is off (empty bootstrap list) unless `env` says otherwise, so no test
    reaches the real network."""
    cmd = [sys.executable, "-c", "from src.main import main; main()", "get", *args]
    full = {**os.environ, "ANYDM_DHT_BOOTSTRAP": "", **(env or {})}
    return subprocess.Popen(cmd, cwd=CLI_DIR, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=full)


def run(*args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    p = anydm(*args, env=env)
    out, err = p.communicate(timeout=60)
    return subprocess.CompletedProcess(p.args, p.returncode, out, err)


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()
