"""Records the API answers under ``src/jvmTest/resources/fixtures``, byte for byte.

It needs only a running API. It serves the files the direct downloads fetch and stands in for
rqbit itself, answering for one torrent (Big Buck Bunny's real file list) whose selected files
grow at about 0.5 MiB/s from 54 peers. Run the API against an empty database, with torrents
pointed here and loopback outside any proxy:

    TORRENT_ENABLED=true TORRENT_API_URL=http://127.0.0.1:3131 NO_PROXY=127.0.0.1 \\
        uvicorn src.main:app --host 127.0.0.1 --port 8046
    python record_fixtures.py src/jvmTest/resources/fixtures

The playlist's videos can't be fetched without reaching YouTube, and it doesn't matter: they are
paused once their first tries are over. A pause while a worker holds a video is overwritten when
that try fails, so the recording waits for the tries before pausing.
"""

import gzip
import json
import os
import random
import re
import sys
import tempfile
import threading
import time
from functools import partial
from http.server import BaseHTTPRequestHandler, SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import httpx

API = "http://127.0.0.1:8046"
FILES = "http://127.0.0.1:8098"
HASH = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
NAME = "Big Buck Bunny"
MAGNET = (
    f"magnet:?xt=urn:btih:{HASH}&dn=Big+Buck+Bunny"
    "&tr=udp%3A%2F%2Fexplodie.org%3A6969&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337"
)
TORRENT_FILES = [("Big Buck Bunny.en.srt", 140), ("Big Buck Bunny.mp4", 276134947), ("poster.jpg", 310380)]
RATE = 512 * 1024
PLAYLIST = "PLwP_SiAcdui0KVebT0mU9Apz359a4ubsC"


# rqbit's side: the endpoints RqbitClient calls, for the one torrent.

held: dict[str, dict] = {}


def _details(only=None, folder=""):
    files = [
        {"name": n, "length": size, "included": only is None or i in only} for i, (n, size) in enumerate(TORRENT_FILES)
    ]
    return {"id": 1, "details": {"info_hash": HASH, "name": NAME, "files": files}, "output_folder": folder}


def _stats(entry):
    only, paused = entry["only"], entry["paused"]
    total = sum(size for i, (_, size) in enumerate(TORRENT_FILES) if i in only)
    got = min(total, entry["base"] + (0 if paused else int((time.time() - entry["since"]) * RATE)))
    left, file_progress = got, []
    for i, (_, size) in enumerate(TORRENT_FILES):
        take = min(size, left) if i in only else 0
        left -= take
        file_progress.append(take)
    stats = {
        "state": "paused" if paused else "live",
        "finished": got >= total,
        "progress_bytes": got,
        "uploaded_bytes": 0,
        "total_bytes": total,
        "file_progress": file_progress,
        "error": None,
    }
    if not paused:
        stats["live"] = {
            "download_speed": {"mbps": RATE / 1024 / 1024},
            "upload_speed": {"mbps": 0},
            "snapshot": {"peer_stats": {"live": 54}},
            "time_remaining": {"duration": {"secs": (total - got) // RATE, "nanos": 0}},
        }
    return stats


class Rqbit(BaseHTTPRequestHandler):
    def _send(self, payload, code=200):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if urlparse(self.path).path != "/torrents":
            return self._send({"error": "not found"}, 404)
        self._send({"torrents": [{"id": 1, "info_hash": h, "name": NAME, "stats": _stats(e)} for h, e in held.items()]})

    def do_POST(self):
        url = urlparse(self.path)
        query = {key: values[0] for key, values in parse_qs(url.query).items()}
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if url.path == "/torrents/limits":
            return self._send({})
        if url.path == "/torrents":
            if query.get("list_only") == "true":
                return self._send(_details())
            every = set(range(len(TORRENT_FILES)))
            only = {int(i) for i in query["only_files"].split(",")} if "only_files" in query else every
            held[HASH] = {"only": only, "base": 0, "since": time.time(), "paused": False}
            return self._send(_details(only, query.get("output_folder", "")))
        match = re.fullmatch(r"/torrents/([0-9a-f]{40})/(pause|start|delete|forget)", url.path)
        if not match or match[1] not in held:
            return self._send({"error": "not found"}, 404)
        entry = held[match[1]]
        if match[2] == "pause" and not entry["paused"]:
            entry.update(base=_stats(entry)["progress_bytes"], paused=True)
        elif match[2] == "start" and entry["paused"]:
            entry.update(paused=False, since=time.time())
        elif match[2] in ("delete", "forget"):
            held.pop(match[1])
        self._send({})

    def log_message(self, *args):
        pass


class Quiet(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


class Server(ThreadingHTTPServer):
    def handle_error(self, request, client_address):
        pass  # The API hangs up early on the odd request; nothing here needs to hear about it.


def _serve(port: int, handler) -> None:
    server = Server(("127.0.0.1", port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()


def _write_files(folder: Path) -> None:
    random.seed(7)
    words = ["debian", "pool", "main", "dists", "binary", "amd64", "Packages", "Release", "README", "doc"]
    (folder / "README").write_text(
        ("See https://www.debian.org/ for information about Debian GNU/Linux.\n" * 18)[:1198]
    )
    lines = "".join(
        f"-rw-r--r-- 1 ftp ftp {random.randint(100, 10**7)} Oct  1 12:00 {'/'.join(random.choices(words, k=4))}\n"
        for _ in range(400000)
    )
    (folder / "ls-lR.gz").write_bytes(gzip.compress(lines.encode(), 6))
    (folder / "small.bin").write_bytes(os.urandom(256 * 1024))


# The recording.

http = httpx.Client(base_url=API, trust_env=False, timeout=30)


def ok(response: httpx.Response) -> httpx.Response:
    if response.status_code >= 400:
        raise SystemExit(f"{response.request.method} {response.request.url} -> {response.status_code} {response.text}")
    return response


def save(out: Path, name: str, response: httpx.Response) -> None:
    (out / name).write_bytes(ok(response).content)
    print("saved", name)


def wait_for(path: str, status: str) -> None:
    end = time.time() + 60
    while time.time() < end:
        if ok(http.get(path)).json()["data"]["status"] == status:
            return
        time.sleep(0.5)
    raise SystemExit(f"{path} never reached {status}")


def settled(collection: str) -> None:
    """Its videos' tries are over: none is downloading."""
    end = time.time() + 60
    while time.time() < end:
        rows = ok(http.get(f"/collection/{collection}/downloads")).json()["data"]
        if rows and all(row["status"] != "downloading" and row["attempts"] > 0 for row in rows):
            return
        time.sleep(0.5)
    raise SystemExit("the playlist's videos never settled")


def api_up() -> None:
    end = time.time() + 60
    while time.time() < end:
        try:
            if http.get("/health/check").status_code == 200:
                return
        except httpx.TransportError:
            pass
        time.sleep(0.5)
    raise SystemExit(f"no API at {API}")


def add_url(name: str) -> str:
    return ok(http.post("/download/url", json={"url": f"{FILES}/{name}"})).json()["data"]["id"]


def record(out: Path) -> None:
    # A finished direct download.
    readme = add_url("README")
    wait_for(f"/download/{readme}", "completed")

    # A two-video playlist, paused.
    entries = [
        {
            "index": 1,
            "id": "-7j3PfXBtlI",
            "url": "https://www.youtube.com/watch?v=-7j3PfXBtlI",
            "title": "Not my department",
        },
        {
            "index": 2,
            "id": "QNmJnL93Gb8",
            "url": "https://www.youtube.com/watch?v=QNmJnL93Gb8",
            "title": "Not my department (2)",
        },
    ]
    collection = ok(
        http.post(
            "/collection",
            json={
                "url": f"https://www.youtube.com/playlist?list={PLAYLIST}",
                "extractor": "YoutubeTab",
                "external_id": PLAYLIST,
                "title": "29C3: Not my department",
                "preset": "480",
                "entries": entries,
            },
        )
    ).json()["data"]["id"]
    settled(collection)
    ok(http.post(f"/collection/{collection}/pause"))

    # A torrent, its video file only.
    torrent = ok(http.post("/download/torrent", json={"torrent": MAGNET, "files": [1]})).json()["data"]["id"]

    # Another finished direct download.
    listing = add_url("ls-lR.gz")
    wait_for(f"/download/{listing}", "completed")
    time.sleep(3)  # A few monitor ticks, so the torrent has bytes and a swarm.

    save(out, "task_direct.json", http.get(f"/download/{readme}"))
    save(out, "task_torrent.json", http.get(f"/download/{torrent}"))
    save(out, "task_group.json", http.get(f"/collection/{collection}"))
    save(out, "page.json", http.get("/download", params={"page": 1, "page_size": 50}))
    save(out, "summary.json", http.get("/download/summary"))

    # Fifteen seconds of the event stream: the snapshot, the torrent's progress, a direct
    # download added and finished, and the playlist resumed and paused again.
    frames = bytearray()

    def listen() -> None:
        with (
            httpx.Client(trust_env=False, timeout=None) as client,
            client.stream("GET", f"{API}/download/events") as response,
        ):
            started = time.time()
            for chunk in response.iter_raw():
                frames.extend(chunk)
                if time.time() - started > 15:
                    return

    listener = threading.Thread(target=listen)
    listener.start()
    time.sleep(2)
    add_url("small.bin")
    time.sleep(4)
    ok(http.post(f"/collection/{collection}/resume"))
    time.sleep(1)
    settled(collection)
    ok(http.post(f"/collection/{collection}/pause"))
    listener.join()
    text = frames.decode()
    # A frame cut off by the end of the recording is left out.
    end = max(text.rfind("\r\n\r\n") + 4, text.rfind("\n\n") + 2)
    (out / "events.txt").write_text(text[:end], newline="")
    print("saved events.txt")


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as served:
        _write_files(Path(served))
        _serve(3131, Rqbit)
        _serve(8098, partial(Quiet, directory=served))
        api_up()
        record(Path(sys.argv[1]))
