"""The fragment path end to end: real yt-dlp and ffmpeg, against a stream served here.

ffmpeg generates an HLS stream, as MPEG-TS and as fMP4, and a local HTTP
server serves it, optionally slowly enough to stop mid-way. Nothing leaves
the machine. yt-dlp's generic extractor reads the playlist, as it would any
site's, and names its one format "0".

Marked ``integration`` for the real binaries. Skipped without ffmpeg, which
CI installs.
"""

import json
import shutil
import subprocess
import threading
import time
from collections.abc import Iterator
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace

import pytest
from src.data.type import Kind
from src.lib.site.client import YtDlpClient
from src.service.download.downloader import Stopped
from src.service.download.fragment import FragmentDownloader
from src.service.download.post_process import FfmpegPostProcessor
from src.service.download.progress import AggregateSample
from src.service.download.rate_limit import Unlimited

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
        reason="needs ffmpeg and ffprobe",
    ),
]

DURATION_S = 20


def _handler(delay_s: float) -> type[SimpleHTTPRequestHandler]:
    """A quiet file handler that waits ``delay_s`` before each fragment, so a test can stop mid-way."""

    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, format: str, *args: object) -> None:
            return None

        def do_GET(self) -> None:
            if delay_s and self.path.endswith((".ts", ".m4s")):
                time.sleep(delay_s)
            super().do_GET()

    return Handler


@pytest.fixture(scope="module")
def streams(tmp_path_factory: pytest.TempPathFactory) -> Path:
    root = tmp_path_factory.mktemp("hls")
    for kind, extra in (("ts", []), ("fmp4", ["-hls_segment_type", "fmp4"])):
        (root / kind).mkdir()
        subprocess.run(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", f"testsrc=size=640x360:rate=25:duration={DURATION_S}",
                "-f", "lavfi", "-i", f"sine=frequency=440:duration={DURATION_S}",
                "-c:v", "libx264", "-b:v", "1000k", "-c:a", "aac",
                "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", *extra,
                str(root / kind / "index.m3u8"),
            ],
            check=True,
        )
    return root


def _serve(root: Path, delay_s: float) -> Iterator[str]:
    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(_handler(delay_s), directory=str(root)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()


@pytest.fixture(scope="module")
def fast(streams: Path) -> Iterator[str]:
    yield from _serve(streams, 0.0)


@pytest.fixture(scope="module")
def slow(streams: Path) -> Iterator[str]:
    yield from _serve(streams, 0.3)


def _probe(path: Path) -> tuple[str, list[str], float]:
    """The container ffprobe finds, the codecs of its streams, and its duration."""
    out = subprocess.run(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "format=format_name,duration:stream=codec_name",
            "-of", "json", str(path),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    data = json.loads(out.stdout)
    codecs = sorted({stream["codec_name"] for stream in data["streams"]})
    return data["format"]["format_name"], codecs, float(data["format"]["duration"])


async def _format_id(client: YtDlpClient, url: str) -> str:
    info = await client.extract(url)
    return next(f.id for f in info.formats if f.media)


def _downloader(client: YtDlpClient, *, concurrency: int = 4, rate_bps: int = 0) -> FragmentDownloader:
    return FragmentDownloader(client, concurrency=concurrency, rate_bps=rate_bps, limiter=Unlimited(), poll_s=0.1)


async def _ignore(_sample: AggregateSample) -> None:
    return None


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["ts", "fmp4"])
async def test_an_hls_stream_downloads_and_remuxes_into_a_playable_mp4(kind: str, fast: str, tmp_path: Path) -> None:
    client = YtDlpClient()
    url = f"{fast}/{kind}/index.m3u8"
    part = tmp_path / "video.part"

    written = await _downloader(client).fetch(
        url, await _format_id(client, url), part, on_sample=_ignore, should_stop=lambda: False
    )
    assert written > 0

    destination = tmp_path / "out.mp4"
    await FfmpegPostProcessor("ffmpeg").run(
        SimpleNamespace(kind=Kind.VIDEO), {"video": part}, destination, fragmented=frozenset({"video"})
    )

    format_name, codecs, duration = _probe(destination)
    assert "mp4" in format_name
    assert codecs == ["aac", "h264"]
    assert abs(duration - DURATION_S) < 1


@pytest.mark.asyncio
async def test_a_download_stopped_mid_way_resumes_into_a_whole_file(slow: str, tmp_path: Path) -> None:
    client = YtDlpClient()
    url = f"{slow}/ts/index.m3u8"
    format_id = await _format_id(client, url)
    part = tmp_path / "video.part"
    seen: list[int] = []

    async def note(sample: AggregateSample) -> None:
        seen.append(sample.downloaded_bytes)

    with pytest.raises(Stopped):
        await _downloader(client, concurrency=1).fetch(
            url, format_id, part, on_sample=note, should_stop=lambda: bool(seen and seen[-1] > 0)
        )
    assert any(tmp_path.iterdir()), "a stop keeps what was fetched"

    await _downloader(client).fetch(url, format_id, part, on_sample=_ignore, should_stop=lambda: False)

    assert abs(_probe(part)[2] - DURATION_S) < 1


@pytest.mark.asyncio
async def test_a_capped_download_stays_near_its_cap(fast: str, streams: Path, tmp_path: Path) -> None:
    client = YtDlpClient()
    url = f"{fast}/ts/index.m3u8"
    format_id = await _format_id(client, url)
    size = sum(p.stat().st_size for p in (streams / "ts").glob("*.ts"))
    cap = 1_000_000

    start = time.monotonic()
    await _downloader(client, concurrency=1, rate_bps=cap).fetch(
        url, format_id, tmp_path / "video.part", on_sample=_ignore, should_stop=lambda: False
    )
    elapsed = time.monotonic() - start

    # Never more than a fifth over the cap. The probe measured 0.96 of it.
    assert size / elapsed <= cap * 1.2
