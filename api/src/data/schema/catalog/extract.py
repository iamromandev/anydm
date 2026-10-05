from __future__ import annotations

from typing import Annotated, Literal

from pydantic import Field

from src.core.base import BaseSchema
from src.data.type import Preset


class ExtractRequest(BaseSchema):
    url: Annotated[str, Field(min_length=1, description="The page to inspect, on any supported site")]


class FormatSchema(BaseSchema):
    id: Annotated[str, Field(description="The site's format id; a YouTube itag, as a string")]
    protocol: str = ""
    ext: str = ""
    height: int | None = None
    has_video: bool = False
    has_audio: bool = False
    #: HLS, DASH and the like, which yt-dlp's downloader fetches rather than the engine.
    fragmented: bool = False
    size: int | None = None
    size_approx: int | None = None


class ExtractSchema(BaseSchema):
    type: Literal["media"] = "media"
    #: yt-dlp's name for the site: "Youtube", "Vimeo", ...
    extractor: str
    #: The site's own id for the media.
    id: str
    title: str = ""
    uploader: str = ""
    duration: int = 0
    thumbnail: str = ""
    webpage_url: str = ""
    formats: Annotated[list[FormatSchema], Field(default_factory=list)]
    #: The presets that can be downloaded today, in the order to offer them.
    presets: Annotated[list[Preset], Field(default_factory=list)]
    #: The playlist a YouTube watch link also names (``list=``), for "see all".
    playlist_url: str | None = None


class TabSchema(BaseSchema):
    name: str
    url: str


class PlaylistSchema(BaseSchema):
    """A list of videos: a playlist or a channel's tab (``playlist``), or a channel home (``channel``)."""

    type: Literal["playlist", "channel"]
    extractor: str
    id: str
    title: str = ""
    uploader: str = ""
    thumbnail: str = ""
    webpage_url: str = ""
    #: How many videos, when the site says.
    count: int | None = None
    #: A channel's own uploads, which part 2 won't number.
    channel_tab: bool = False
    tabs: Annotated[list[TabSchema], Field(default_factory=list)]
    #: Every preset: a listing has no formats to narrow them by.
    presets: Annotated[list[Preset], Field(default_factory=list)]
