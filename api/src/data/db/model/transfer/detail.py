from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import MediaKind, Preset


class SiteDetail(LinkBase):
    """What only a site download has: what it is called, what it is, and the formats it fetches.

    Which site, and its id for the video, are the provider and ref of the
    source its mirror points at, so neither is repeated here. A playlist or
    channel tab is a download too; its kind and preset live in its own row.
    """

    download = fields.OneToOneField("model.Download", related_name="site_detail", on_delete=fields.CASCADE)
    #: The title the extract returned.
    title: str = fields.CharField(max_length=512, default="")
    media_kind: MediaKind = fields.CharEnumField(MediaKind, max_length=8, default=MediaKind.VIDEO)
    preset: Preset = fields.CharEnumField(Preset, max_length=8)
    video_format: str | None = fields.CharField(max_length=64, null=True)
    audio_format: str | None = fields.CharField(max_length=64, null=True)
    #: Its place in the playlist it was added from; none for a standalone video.
    playlist_index: int | None = fields.IntField(null=True)

    if TYPE_CHECKING:
        download_id: UUID

    def __str__(self) -> str:
        return f"[SiteDetail: download {self.download_id}, {self.media_kind}]"

    class Meta:
        table: ClassVar[str] = "site_detail"
        table_description: ClassVar[str] = "SiteDetail"
        schema: ClassVar[str] = "transfer"
