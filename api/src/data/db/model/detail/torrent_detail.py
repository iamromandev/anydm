from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class TorrentDetail(LinkBase):
    """What only a torrent has.

    The info hash is the only torrent identifier stored: rqbit accepts it
    anywhere it accepts its own numeric id, which does not survive a restart.
    """

    download = fields.OneToOneField("model.Download", related_name="torrent_detail", on_delete=fields.CASCADE)
    info_hash: str = fields.CharField(max_length=40, unique=True)
    #: Stored so the share ratio needs no second source.
    uploaded_bytes: int = fields.BigIntField(default=0)

    class Meta:
        table: ClassVar[str] = "torrent_detail"
        table_description: ClassVar[str] = "TorrentDetail"
        schema: ClassVar[str] = "transfer"
