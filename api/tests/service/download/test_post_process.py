from pathlib import Path
from types import SimpleNamespace

import pytest
from src.core.error import Error
from src.data.type import Kind
from src.service.download.post_process import FfmpegPostProcessor


class SpyRunner:
    def __init__(self) -> None:
        self.calls: list[list[str]] = []

    async def __call__(self, args: list[str]) -> None:
        self.calls.append(args)
        # Stand in for ffmpeg writing its output.
        Path(args[-1]).write_bytes(b"encoded")


@pytest.mark.asyncio
async def test_a_single_part_is_renamed_without_calling_ffmpeg(tmp_path: Path) -> None:
    part = tmp_path / "video.part"
    part.write_bytes(b"data")
    destination = tmp_path / "clip.mp4"
    runner = SpyRunner()

    task = SimpleNamespace(kind=Kind.VIDEO, audio_itag=None, video_itag=137)
    await FfmpegPostProcessor("ffmpeg", runner).run(task, {"video": part}, destination)

    assert runner.calls == []
    assert destination.read_bytes() == b"data"
    assert not part.exists()


@pytest.mark.asyncio
async def test_a_direct_download_part_is_also_just_renamed(tmp_path: Path) -> None:
    part = tmp_path / "file.part"
    part.write_bytes(b"data")
    destination = tmp_path / "archive.zip"

    task = SimpleNamespace(kind=Kind.FILE, audio_itag=None, video_itag=None)
    await FfmpegPostProcessor("ffmpeg", SpyRunner()).run(task, {"file": part}, destination)

    assert destination.read_bytes() == b"data"


@pytest.mark.asyncio
async def test_video_plus_audio_is_muxed(tmp_path: Path) -> None:
    video, audio = tmp_path / "video.part", tmp_path / "audio.part"
    video.write_bytes(b"v")
    audio.write_bytes(b"a")
    destination = tmp_path / "clip.mp4"
    runner = SpyRunner()

    task = SimpleNamespace(kind=Kind.VIDEO, audio_itag=140, video_itag=137)
    await FfmpegPostProcessor("ffmpeg", runner).run(task, {"video": video, "audio": audio}, destination)

    assert len(runner.calls) == 1
    assert "-c" in runner.calls[0]
    assert destination.read_bytes() == b"encoded"


@pytest.mark.asyncio
async def test_muxing_removes_both_parts(tmp_path: Path) -> None:
    video, audio = tmp_path / "video.part", tmp_path / "audio.part"
    video.write_bytes(b"v")
    audio.write_bytes(b"a")

    task = SimpleNamespace(kind=Kind.VIDEO, audio_itag=140, video_itag=137)
    await FfmpegPostProcessor("ffmpeg", SpyRunner()).run(
        task, {"video": video, "audio": audio}, tmp_path / "clip.mp4"
    )

    assert not video.exists()
    assert not audio.exists()


@pytest.mark.asyncio
async def test_audio_is_transcoded_to_mp3(tmp_path: Path) -> None:
    audio = tmp_path / "audio.part"
    audio.write_bytes(b"a")
    runner = SpyRunner()

    task = SimpleNamespace(kind=Kind.AUDIO, audio_itag=140, video_itag=None)
    await FfmpegPostProcessor("ffmpeg", runner).run(task, {"audio": audio}, tmp_path / "clip.mp3")

    # An MP3 task has exactly one part, so it must be transcoded rather than
    # renamed — the audio stream is AAC or Opus, not MP3.
    assert len(runner.calls) == 1
    assert "libmp3lame" in runner.calls[0]
    assert not audio.exists()


@pytest.mark.asyncio
async def test_no_parts_at_all_is_an_error(tmp_path: Path) -> None:
    task = SimpleNamespace(kind=Kind.VIDEO, audio_itag=None, video_itag=137)
    with pytest.raises(Error):
        await FfmpegPostProcessor("ffmpeg", SpyRunner()).run(task, {}, tmp_path / "clip.mp4")
