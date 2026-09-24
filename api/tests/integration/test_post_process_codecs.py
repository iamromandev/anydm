"""The post-processor against a real ffmpeg, over clips ffmpeg makes itself.

The unit tests check the arguments. Only ffmpeg can say whether a container
takes a pair of codecs, so here each pair is muxed into the container
``container_for`` chooses and read back with ffprobe, and each audio codec is
made into an MP3. The parts are named as the worker names them, ``*.part``, so
ffmpeg recognises them by their contents as it does in production.

Marked ``integration`` for the real binary, not for a database: nothing here
touches one. Skipped where ffmpeg is missing; CI installs it.
"""

import json
import shutil
import subprocess
from pathlib import Path
from types import SimpleNamespace

import pytest
from src.data.type import Kind
from src.lib.site.format import container_for
from src.service.download.post_process import FfmpegPostProcessor

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
        reason="needs ffmpeg and ffprobe",
    ),
]

#: Per codec: the string yt-dlp reports for it, how to encode a clip, and the
#: extension that clip is written with.
VIDEO = {
    "h264": ("avc1.640028", ["-c:v", "libx264"], "mp4"),
    "vp9": ("vp9", ["-c:v", "libvpx-vp9", "-b:v", "200k"], "webm"),
    "vp8": ("vp8", ["-c:v", "libvpx", "-b:v", "200k"], "webm"),
}
AUDIO = {
    "aac": ("mp4a.40.2", ["-c:a", "aac"], "m4a"),
    "opus": ("opus", ["-c:a", "libopus"], "webm"),
    "vorbis": ("vorbis", ["-c:a", "libvorbis"], "webm"),
}

#: Every container the rule can choose: two pairs for MP4, two for WebM, two
#: for MKV.
PAIRS = [
    ("h264", "aac", "mp4"),
    ("vp9", "opus", "mp4"),
    ("vp8", "vorbis", "webm"),
    ("vp9", "vorbis", "webm"),
    ("h264", "vorbis", "mkv"),
    ("vp8", "aac", "mkv"),
]


def _ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def _probe(path: Path) -> tuple[str, list[str]]:
    """The container ffprobe finds, and the codecs of the streams in it."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=format_name:stream=codec_name", "-of", "json", str(path)],
        capture_output=True,
        text=True,
        check=True,
    )
    data = json.loads(out.stdout)
    return data["format"]["format_name"], sorted(stream["codec_name"] for stream in data["streams"])


def _part(clip: Path, destination: Path) -> Path:
    # A copy: the post-processor deletes the parts it has used.
    return Path(shutil.copy(clip, destination))


@pytest.fixture(scope="module")
def clips(tmp_path_factory: pytest.TempPathFactory) -> dict[str, Path]:
    folder = tmp_path_factory.mktemp("clips")
    made: dict[str, Path] = {}
    for name, (_, encoder, extension) in VIDEO.items():
        made[name] = folder / f"{name}.{extension}"
        _ffmpeg("-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=1", *encoder, "-an", str(made[name]))
    for name, (_, encoder, extension) in AUDIO.items():
        made[name] = folder / f"{name}.{extension}"
        _ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=1", *encoder, "-vn", str(made[name]))
    return made


@pytest.mark.asyncio
@pytest.mark.parametrize(("video", "audio", "container"), PAIRS, ids=[f"{v}+{a}-{c}" for v, a, c in PAIRS])
async def test_two_parts_mux_into_the_container_chosen_for_them(
    video: str, audio: str, container: str, clips: dict[str, Path], tmp_path: Path
) -> None:
    extension, _ = container_for(VIDEO[video][0], AUDIO[audio][0])
    assert extension == container

    parts = {
        "video": _part(clips[video], tmp_path / "video.part"),
        "audio": _part(clips[audio], tmp_path / "audio.part"),
    }
    destination = tmp_path / f"out.{extension}"

    await FfmpegPostProcessor("ffmpeg").run(SimpleNamespace(kind=Kind.VIDEO), parts, destination)

    format_name, codecs = _probe(destination)
    assert codecs == sorted([video, audio])
    # ffprobe names MP4 "mov,mp4,…" and both WebM and MKV "matroska,webm".
    assert ("mp4" in format_name) == (extension == "mp4")
    assert not parts["video"].exists() and not parts["audio"].exists()


@pytest.mark.asyncio
@pytest.mark.parametrize("audio", list(AUDIO))
async def test_an_mp3_is_made_from_any_audio_codec(audio: str, clips: dict[str, Path], tmp_path: Path) -> None:
    parts = {"audio": _part(clips[audio], tmp_path / "audio.part")}
    destination = tmp_path / "out.mp3"

    await FfmpegPostProcessor("ffmpeg").run(SimpleNamespace(kind=Kind.AUDIO), parts, destination)

    assert _probe(destination)[1] == ["mp3"]


@pytest.mark.asyncio
async def test_an_hls_part_is_remuxed_into_a_playable_mp4(tmp_path: Path) -> None:
    # What yt-dlp's HLS downloader writes: raw MPEG-TS, H.264 and ADTS AAC.
    part = tmp_path / "video.part"
    _ffmpeg(
        "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=1",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        "-c:v", "libx264", "-c:a", "aac", "-f", "mpegts", str(part),
    )
    destination = tmp_path / "out.mp4"

    await FfmpegPostProcessor("ffmpeg").run(
        SimpleNamespace(kind=Kind.VIDEO), {"video": part}, destination, fragmented=frozenset({"video"})
    )

    format_name, codecs = _probe(destination)
    assert "mp4" in format_name
    assert codecs == ["aac", "h264"]
