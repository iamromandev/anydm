from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar
from uuid import UUID

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import MediaKind, Preset


class Media(LinkBase):
    """What a site download fetches: what it is called, what kind it is, and the formats it takes.

    Which site it came from is the provider of the source its mirror points
    at, so it is not repeated here. A playlist or
    channel tab is a download too; its kind and preset live in its own row.
    A torrent has none: its counterpart is ``Torrent``.
    """

    download = fields.OneToOneField("model.Download", related_name="media", on_delete=fields.CASCADE)
    #: The title the extract returned.
    title: str = fields.CharField(max_length=512, default="")
    kind: MediaKind = fields.CharEnumField(MediaKind, max_length=8, default=MediaKind.VIDEO)
    preset: Preset = fields.CharEnumField(Preset, max_length=8)
    video_format: str | None = fields.CharField(max_length=64, null=True)
    audio_format: str | None = fields.CharField(max_length=64, null=True)
    #: Its place in the playlist it was added from; none for a standalone video.
    playlist_index: int | None = fields.IntField(null=True)

    if TYPE_CHECKING:
        download_id: UUID

    def __str__(self) -> str:
        return f"[Media: download {self.download_id}, {self.kind}]"

    class Meta:
        table: ClassVar[str] = "media"
        table_description: ClassVar[str] = "Media"
        schema: ClassVar[str] = "transfer"
