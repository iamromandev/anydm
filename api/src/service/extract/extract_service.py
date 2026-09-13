from __future__ import annotations

from src.core.base import BaseService
from src.data.schema.extract import ExtractSchema, FormatSchema, ThumbnailSchema
from src.lib.youtube import StreamInfo, VideoInfo, YouTubeClient, extract_video_id, not_a_youtube_url


def _container_of(mime_type: str | None) -> str:
    """``"video/mp4; codecs=..."`` becomes ``"mp4"``."""
    if not mime_type:
        return "unknown"
    subtype = mime_type.split(";")[0].split("/")
    return subtype[1] if len(subtype) > 1 and subtype[1] else "unknown"


def _to_format(stream: StreamInfo) -> FormatSchema:
    return FormatSchema(
        itag=stream.itag,
        quality=stream.quality or "unknown",
        container=_container_of(stream.mime_type),
        has_video=stream.has_video,
        has_audio=stream.has_audio,
        content_length=stream.content_length,
        mime_type=stream.mime_type,
    )


class ExtractService(BaseService):
    def __init__(self, client: YouTubeClient) -> None:
        super().__init__()
        self._client = client

    async def extract(self, url: str) -> ExtractSchema:
        video_id = extract_video_id(url)
        if video_id is None:
            raise not_a_youtube_url()

        info: VideoInfo = await self._client.fetch_info(video_id)
        thumbnails = [ThumbnailSchema(url=t.url, width=t.width, height=t.height) for t in info.thumbnails]

        return ExtractSchema(
            platform="youtube",
            video_id=info.video_id,
            title=info.title,
            author=info.author,
            channel_id=info.channel_id,
            description=info.description,
            length_seconds=info.length_seconds,
            view_count=info.view_count,
            upload_date=info.upload_date,
            is_live=info.is_live,
            # Last wins: the client returns thumbnails smallest-first, and the
            # UI wants the biggest one it can get.
            thumbnail=thumbnails[-1].url if thumbnails else "",
            thumbnails=thumbnails,
            formats=[_to_format(stream) for stream in info.streams],
        )
