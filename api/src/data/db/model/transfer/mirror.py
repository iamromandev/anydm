from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase


class Mirror(LinkBase):
    """One alternate address for one download, tried in ``position`` order.

    The worker moves to the next mirror when the current one fails for good,
    recording ``last_error`` on the one it left.
    """

    download = fields.ForeignKeyField("model.Download", related_name="mirrors", on_delete=fields.CASCADE)
    url = fields.ForeignKeyField("model.Url", related_name="+", on_delete=fields.CASCADE)
    position: int = fields.IntField(default=0)
    last_error: str | None = fields.TextField(null=True)

    def __str__(self) -> str:
        return f"[Mirror: download {self.download_id}, position {self.position}]"

    class Meta:
        table: ClassVar[str] = "mirror"
        table_description: ClassVar[str] = "Mirror"
        schema: ClassVar[str] = "transfer"
        ordering: ClassVar[list[str]] = ["position"]
        unique_together: ClassVar[tuple[tuple[str, ...], ...]] = (("download", "position"),)
