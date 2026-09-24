"""The player's HLS path end to end: a real ffmpeg cutting segments from streams served here (#87).

ffmpeg makes two HLS streams, and a local HTTP server serves them. Nothing
leaves the machine.

- **TS:** video and audio together, in 2 s fragments.
- **fMP4:** video and audio in separate playlists, in 3 s and 2 s
  fragments, so a segment's two inputs never start together.

A ``StreamService`` over a fake site whose formats are those playlists cuts
every segment, and ffprobe checks each one is whole: video and audio of the
segment's length, starting together. The input seek the player used before
wrote a segment 1 of the fMP4 stream that ffprobe couldn't read.

The tolerances are what ffmpeg's own TS gives. Its audio leads its video by
about 0.08 s in every fragment, and a cut starts at the earliest packet, so
TS segments come out up to 0.22 s short and 0.1 s apart.

Marked ``integration`` for the real binaries. Skipped without ffmpeg, which
CI installs.
"""

import json
import shutil
import subprocess
import threading
from collections.abc import Iterator
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
from src.core.error import Error
from src.core.type import Code
from src.lib.site.client import Resolved, SiteInfo
from src.lib.site.format import Format
from src.service.stream.session import StreamSessionStore
from src.service.stream.stream_service import StreamService

from tests.sites import FakeSiteClient

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
        reason="needs ffmpeg and ffprobe",
    ),
]

DURATION_S = 20
PAGE = "https://site.test/watch/1"

#: A site's HLS comes combined, or as a video and an audio.
FORMATS = {
    "ts": [Format("hls-av", protocol="m3u8_native", ext="mp4", vcodec="avc1.64001e", acodec="mp4a.40.2", height=360)],
    "fmp4": [
        Format("hls-v", protocol="m3u8_native", ext="mp4", vcodec="avc1.64001e", acodec="none", height=360),
        Format("hls-a", protocol="m3u8_native", ext="mp4", vcodec="none", acodec="mp4a.40.2"),
    ],
}
PLAYLISTS = {"hls-av": "ts/index.m3u8", "hls-v": "fmp4/video.m3u8", "hls-a": "fmp4/audio.m3u8"}


def _ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


@pytest.fixture(scope="module")
def streams(tmp_path_factory: pytest.TempPathFactory) -> Path:
    root = tmp_path_factory.mktemp("hls")
    (root / "ts").mkdir()
    (root / "fmp4").mkdir()
    video = ["-f", "lavfi", "-i", f"testsrc=size=640x360:rate=25:duration={DURATION_S}"]
    audio = ["-f", "lavfi", "-i", f"sine=frequency=440:duration={DURATION_S}"]
    hls = ["-f", "hls", "-hls_playlist_type", "vod"]
    fmp4 = ["-hls_segment_type", "fmp4"]
    # A keyframe at every fragment's start, as a site's streams have.
    _ffmpeg(
        *video, *audio, "-c:v", "libx264", "-g", "50", "-c:a", "aac",
        *hls, "-hls_time", "2", str(root / "ts" / "index.m3u8"),
    )
    _ffmpeg(
        *video, "-c:v", "libx264", "-g", "75", "-an", *hls, "-hls_time", "3", *fmp4,
        "-hls_fmp4_init_filename", "video_init.mp4",
        "-hls_segment_filename", str(root / "fmp4" / "video_%d.m4s"),
        str(root / "fmp4" / "video.m3u8"),
    )
    _ffmpeg(
        *audio, "-c:a", "aac", "-vn", *hls, "-hls_time", "2", *fmp4,
        "-hls_fmp4_init_filename", "audio_init.mp4",
        "-hls_segment_filename", str(root / "fmp4" / "audio_%d.m4s"),
        str(root / "fmp4" / "audio.m3u8"),
    )
    return root


class _Quiet(SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        return None


@pytest.fixture(scope="module")
def served(streams: Path) -> Iterator[str]:
    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(_Quiet, directory=str(streams)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()


class _LocalSite(FakeSiteClient):
    """A site whose formats are the playlists served here, which want no headers."""

    def __init__(self, formats: list[Format], urls: dict[str, str]) -> None:
        super().__init__(SiteInfo(extractor="Local", id="1", title="Local", webpage_url=PAGE, formats=formats))
        self.urls = urls

    def _resolved(self, format_id: str) -> Resolved:
        return Resolved(self.urls[format_id], {}, fragmented=True)


def _service(tmp_path: Path, site: _LocalSite) -> StreamService:
    return StreamService(
        sessions=StreamSessionStore(),
        stream_dir=tmp_path,
        ffmpeg_path="ffmpeg",
        ffprobe_path="ffprobe",
        segment_seconds=6,
        readahead_segments=0,
        max_concurrent_encodes=2,
        site_client=site,
    )


def _streams(path: Path) -> dict[str, tuple[float, float]]:
    """Each stream's start and duration, by kind."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,start_time,duration", "-of", "json", str(path)],
        capture_output=True,
        text=True,
        check=True,
    )
    return {
        stream["codec_type"]: (float(stream["start_time"]), float(stream["duration"]))
        for stream in json.loads(out.stdout)["streams"]
    }


@pytest.mark.asyncio
@pytest.mark.parametrize("shape", ["ts", "fmp4"])
async def test_every_segment_of_an_hls_page_is_whole(shape: str, served: str, tmp_path: Path) -> None:
    formats = FORMATS[shape]
    service = _service(tmp_path, _LocalSite(formats, {f.id: f"{served}/{PLAYLISTS[f.id]}" for f in formats}))

    session = await service.start_session(PAGE)

    assert session.segment_count == 4
    for index, expected in enumerate([6.0, 6.0, 6.0, 2.0]):
        streams = _streams(await service.get_segment(session, index))
        assert set(streams) == {"video", "audio"}, index
        for kind, (_, duration) in streams.items():
            assert abs(duration - expected) <= 0.3, (index, kind, duration)
        assert abs(streams["video"][0] - streams["audio"][0]) <= 0.15, (index, streams)


@pytest.mark.asyncio
async def test_a_playlist_that_is_not_there_is_a_bad_gateway(served: str, tmp_path: Path) -> None:
    service = _service(tmp_path, _LocalSite(FORMATS["ts"], {"hls-av": f"{served}/ts/missing.m3u8"}))

    with pytest.raises(Error) as caught:
        await service.start_session(PAGE)

    assert (caught.value.code, caught.value.retry_able) == (Code.BAD_GATEWAY, True)
    assert caught.value.message == "Couldn't read the stream's playlist: status 404"
