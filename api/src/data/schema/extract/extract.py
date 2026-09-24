from typing import Annotated

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
    #: HLS or DASH, which only the fragment path (#58) can fetch.
    fragmented: bool = False
    size: int | None = None
    size_approx: int | None = None


class ExtractSchema(BaseSchema):
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
