from __future__ import annotations

from urllib.parse import parse_qs, urlsplit

from src.core.base import BaseService
from src.data.schema.catalog import ExtractSchema, FormatSchema, PlaylistSchema, TabSchema
from src.data.type import Preset
from src.lib.site import error as site_error
from src.lib.site.client import PlaylistInfo, SiteClient
from src.lib.site.format import Format, usable_presets

#: YouTube's mixes: generated per person, with no page of their own to list.
_YOUTUBE_MIX = "RD"


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


def playlist_url(url: str, extractor: str) -> str | None:
    """The playlist a YouTube watch link also names, as a link to the whole list."""
    if extractor != "Youtube":
        return None
    ids = parse_qs(urlsplit(url).query).get("list") or []
    list_id = ids[0] if ids else ""
    if not list_id or list_id.startswith(_YOUTUBE_MIX):
        return None
    return f"https://www.youtube.com/playlist?list={list_id}"


def _to_playlist(info: PlaylistInfo) -> PlaylistSchema:
    return PlaylistSchema(
        type="channel" if info.tabs else "playlist",
        extractor=info.extractor,
        id=info.id,
        title=info.title,
        uploader=info.uploader,
        thumbnail=info.thumbnail,
        webpage_url=info.webpage_url,
        count=info.count,
        channel_tab=info.channel_tab,
        tabs=[TabSchema(name=tab.name, url=tab.url) for tab in info.tabs],
        presets=list(Preset),
    )


class ExtractService(BaseService):
    def __init__(self, client: SiteClient) -> None:
        super().__init__()
        self._client = client

    async def extract(self, url: str) -> ExtractSchema | PlaylistSchema:
        """What a link is: a video and the presets it can be downloaded at, or a list of videos.

        Refuses what enqueueing would refuse, so a preview never offers a
        download that is bound to fail: live streams.
        """
        info = await self._client.inspect(url)
        if isinstance(info, PlaylistInfo):
            return _to_playlist(info)
        if info.is_live:
            raise site_error.live_not_supported()

        return ExtractSchema(
            extractor=info.extractor,
            id=info.id,
            title=info.title,
            uploader=info.uploader,
            duration=info.duration,
            thumbnail=info.thumbnail,
            webpage_url=info.webpage_url,
            formats=[_to_format(fmt) for fmt in info.formats if fmt.media],
            presets=usable_presets(info.formats),
            playlist_url=playlist_url(url, info.extractor),
        )
