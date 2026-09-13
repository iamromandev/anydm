import pytest
from src.core.error import Error
from src.lib.youtube.protocol import StreamInfo, Thumbnail, VideoInfo
from src.service.extract.extract_service import ExtractService

VIDEO_ID = "dQw4w9WgXcQ"


class FakeClient:
    def __init__(self, info: VideoInfo) -> None:
        self._info = info
        self.calls: list[str] = []

    async def fetch_info(self, video_id: str) -> VideoInfo:
        self.calls.append(video_id)
        return self._info

    async def stream_url(self, video_id: str, itag: int) -> str:
        raise AssertionError("extract must not resolve stream URLs")


def _info() -> VideoInfo:
    return VideoInfo(
        video_id=VIDEO_ID,
        title="Never Gonna Give You Up",
        author="Rick Astley",
        length_seconds=213,
        thumbnails=[
            Thumbnail(url="https://i.ytimg.com/small.jpg", width=120, height=90),
            Thumbnail(url="https://i.ytimg.com/large.jpg", width=1280, height=720),
        ],
        streams=[
            StreamInfo(
                itag=18,
                mime_type="video/mp4",
                quality="360p",
                height=360,
                has_video=True,
                has_audio=True,
                content_length=1000,
            ),
            StreamInfo(itag=140, mime_type="audio/mp4", bitrate=128000, has_audio=True),
        ],
    )


@pytest.mark.asyncio
async def test_extract_maps_metadata_and_formats() -> None:
    client = FakeClient(_info())
    result = await ExtractService(client).extract(f"https://youtu.be/{VIDEO_ID}")

    assert client.calls == [VIDEO_ID]
    assert result.platform == "youtube"
    assert result.video_id == VIDEO_ID
    assert result.title == "Never Gonna Give You Up"
    assert result.length_seconds == 213
    assert len(result.formats) == 2
    assert result.formats[0].itag == 18
    assert result.formats[0].container == "mp4"
    assert result.formats[1].container == "mp4"


@pytest.mark.asyncio
async def test_extract_uses_the_largest_thumbnail() -> None:
    result = await ExtractService(FakeClient(_info())).extract(f"https://youtu.be/{VIDEO_ID}")
    assert result.thumbnail == "https://i.ytimg.com/large.jpg"


@pytest.mark.asyncio
async def test_extract_rejects_a_non_youtube_url() -> None:
    with pytest.raises(Error) as caught:
        await ExtractService(FakeClient(_info())).extract("https://example.com/video")
    assert caught.value.code == 400


@pytest.mark.asyncio
async def test_extract_handles_a_video_with_no_thumbnails() -> None:
    info = VideoInfo(video_id=VIDEO_ID, title="t", thumbnails=[], streams=[])
    result = await ExtractService(FakeClient(info)).extract(f"https://youtu.be/{VIDEO_ID}")
    assert result.thumbnail == ""
    assert result.formats == []
