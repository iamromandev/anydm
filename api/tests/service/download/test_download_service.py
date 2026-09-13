import uuid
from pathlib import Path
from typing import Any

import pytest
from src.core.error import Error
from src.data.type import Kind, Platform, Preset, TaskStatus
from src.lib.youtube.protocol import StreamInfo, VideoInfo
from src.service.download.control import DownloadControl
from src.service.download.download_service import DownloadService

VIDEO_ID = "dQw4w9WgXcQ"


class FakeClient:
    async def fetch_info(self, video_id: str) -> VideoInfo:
        return VideoInfo(
            video_id=video_id,
            title="Never Gonna Give You Up",
            streams=[
                StreamInfo(itag=137, mime_type="video/mp4", quality="1080p", height=1080, has_video=True),
                StreamInfo(itag=140, mime_type="audio/mp4", bitrate=128000, has_audio=True),
            ],
        )

    async def stream_url(self, video_id: str, itag: int) -> str:
        raise AssertionError("enqueue must not resolve stream URLs")


class FakeRepo:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []

    async def create(self, **kwargs: Any) -> Any:
        kwargs.setdefault("id", uuid.uuid4())
        kwargs.setdefault("created_at", None)
        kwargs.setdefault("updated_at", None)
        self.created.append(kwargs)
        return type("Row", (), kwargs)()


def _service(downloads_dir: Path | None = None) -> tuple[DownloadService, FakeRepo]:
    repo = FakeRepo()
    service = DownloadService(
        repo=repo,  # ty: ignore[invalid-argument-type]
        client=FakeClient(),
        control=DownloadControl(),
        downloads_root=downloads_dir or Path("/tmp/anydm-test"),
    )
    return service, repo


@pytest.mark.asyncio
async def test_enqueue_writes_a_pending_row() -> None:
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.P1080)

    assert len(repo.created) == 1
    row = repo.created[0]
    assert row["status"] == TaskStatus.PENDING
    assert row["platform"] == Platform.YOUTUBE
    assert row["video_id"] == VIDEO_ID
    assert row["preset"] == Preset.P1080
    assert row["progress"] == 0


@pytest.mark.asyncio
async def test_enqueue_stores_the_resolved_plan() -> None:
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.P1080)

    row = repo.created[0]
    assert row["kind"] == Kind.VIDEO
    assert row["video_itag"] == 137
    assert row["audio_itag"] == 140
    assert row["filename"] == "Never_Gonna_Give_You_Up_1080p.mp4"
    assert row["title"] == "Never Gonna Give You Up"


@pytest.mark.asyncio
async def test_enqueue_stores_an_mp3_plan() -> None:
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.MP3)

    row = repo.created[0]
    assert row["kind"] == Kind.AUDIO
    assert row["video_itag"] is None
    assert row["audio_itag"] == 140
    assert row["filename"] == "Never_Gonna_Give_You_Up.mp3"


@pytest.mark.asyncio
async def test_enqueue_rejects_a_non_youtube_url() -> None:
    service, _ = _service()
    with pytest.raises(Error) as caught:
        await service.enqueue_youtube("https://example.com/v", Preset.BEST)
    assert caught.value.code == 400


@pytest.mark.asyncio
async def test_a_taller_preset_than_available_falls_back_to_the_tallest() -> None:
    # 1080p is the tallest on offer, so 2160 degrades rather than failing.
    service, repo = _service()
    await service.enqueue_youtube(f"https://youtu.be/{VIDEO_ID}", Preset.P2160)
    assert repo.created[0]["video_itag"] == 137
