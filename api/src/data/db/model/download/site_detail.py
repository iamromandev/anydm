from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import Preset


class SiteDetail(LinkBase):
    """What only a site download has. YouTube is a site: ``extractor`` says which."""

    download = fields.OneToOneField("model.Download", related_name="site_detail", on_delete=fields.CASCADE)
    #: yt-dlp's name for the site: "Youtube", "Vimeo", ...
    extractor: str = fields.CharField(max_length=64)
    #: The site's own id for the video; what the picker's "already have it" asks about.
    video_id: str = fields.CharField(max_length=128, db_index=True)
    preset: Preset = fields.CharEnumField(Preset, max_length=8)
    #: The site's format ids, chosen when the download is planned. A YouTube itag, as a string.
    video_format: str | None = fields.CharField(max_length=64, null=True)
    audio_format: str | None = fields.CharField(max_length=64, null=True)

    class Meta:
        table: ClassVar[str] = "site_detail"
        table_description: ClassVar[str] = "SiteDetail"
