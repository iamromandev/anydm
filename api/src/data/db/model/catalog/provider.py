from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import ProviderStatus


class Provider(LinkBase):
    """Who a source talks to: one row per upstream, holding its key."""

    base_url = fields.OneToOneField(
        to="model.Url",
        related_name="providers",
        null=True,
        on_delete=fields.SET_NULL,
    )
    name = fields.CharField(max_length=64)
    slug = fields.CharField(max_length=64, unique=True)
    status: ProviderStatus = fields.CharEnumField(ProviderStatus, default=ProviderStatus.ACTIVE)

    def __str__(self) -> str:
        return f"[Provider: {self.name}]"

    class Meta:
        table: ClassVar[str] = "provider"
        table_description: ClassVar[str] = "Provider"
        schema: ClassVar[str] = "transfer"
