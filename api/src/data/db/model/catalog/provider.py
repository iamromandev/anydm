from __future__ import annotations

from typing import ClassVar

from tortoise import fields

from src.core.base import LinkBase
from src.data.type import ProviderStatus


class Provider(LinkBase):
    """Who a source talks to: one row per upstream, holding its key.

    A provider with a ``parser`` is a search source: a built-in parser
    (``apibay``, ``nyaa``, ``eztv``) or a Torznab indexer, enabled while its
    ``status`` is active. One without (``http``, ``torrent``, a site's
    extractor) only says whose id a download carries.

    ``api_key`` is stored as given; encrypting stored secrets is #269.
    """

    base_url = fields.OneToOneField(
        to="model.Url",
        related_name="providers",
        null=True,
        on_delete=fields.SET_NULL,
    )
    name = fields.CharField(max_length=64)
    slug = fields.CharField(max_length=64, unique=True)
    status: ProviderStatus = fields.CharEnumField(ProviderStatus, default=ProviderStatus.ACTIVE)
    parser: str | None = fields.CharField(max_length=16, null=True)
    api_key: str | None = fields.CharField(max_length=1024, null=True)

    def __str__(self) -> str:
        return f"[Provider: {self.name}]"

    class Meta:
        table: ClassVar[str] = "provider"
        table_description: ClassVar[str] = "Provider"
        schema: ClassVar[str] = "catalog"
