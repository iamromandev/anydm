from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import Preset


class SiteDetail(LinkBase):
    """What only a site download has. Which site, and its id for the video, are ``Download.provider`` and ``ref_id``."""

    download = fields.OneToOneField("model.Download", related_name="site_detail", on_delete=fields.CASCADE)
    preset: Preset = fields.CharEnumField(Preset, max_length=8)
    video_format: str | None = fields.CharField(max_length=64, null=True)
    audio_format: str | None = fields.CharField(max_length=64, null=True)

    class Meta:
        table: ClassVar[str] = "site_detail"
        table_description: ClassVar[str] = "SiteDetail"
        schema: ClassVar[str] = "transfer"
