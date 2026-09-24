from __future__ import annotations

from src.core.base import BaseService
from src.data.schema.extract import ExtractSchema, FormatSchema
from src.lib.site import error as site_error
from src.lib.site.client import SiteClient
from src.lib.site.format import Format, usable_presets


def _to_format(fmt: Format) -> FormatSchema:
    return FormatSchema(
        id=fmt.id,
        protocol=fmt.protocol,
        ext=fmt.ext,
        height=fmt.height,
        has_video=fmt.has_video,
        has_audio=fmt.has_audio,
        fragmented=fmt.fragmented,
        size=fmt.size,
        size_approx=fmt.size_approx,
    )


class ExtractService(BaseService):
    def __init__(self, client: SiteClient) -> None:
        super().__init__()
        self._client = client

    async def extract(self, url: str) -> ExtractSchema:
        """What a page offers, and which presets can be downloaded from it today.

        Refuses what enqueueing would refuse, so a preview never offers a
        download that is bound to fail: live streams.
        """
        info = await self._client.extract(url)
        if info.is_live:
            raise site_error.live_not_supported()
        presets = usable_presets(info.formats)

        return ExtractSchema(
            extractor=info.extractor,
            id=info.id,
            title=info.title,
            uploader=info.uploader,
            duration=info.duration,
            thumbnail=info.thumbnail,
            webpage_url=info.webpage_url,
            formats=[_to_format(fmt) for fmt in info.formats if fmt.media],
            presets=presets,
        )
