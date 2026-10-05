from __future__ import annotations

import uuid
from collections.abc import Sequence

from src.data.db.model import Provider
from src.data.repo.catalog import url_row
from src.data.repo.search.interface.source import SourceRepo, SourceRow
from src.data.type import ProviderStatus

# A search source is a catalog.Provider with a parser: its name is the slug,
# its kind the parser, its address the Url its base_url points at, and it is
# enabled while its status is active.


def _status(enabled: bool) -> ProviderStatus:
    return ProviderStatus.ACTIVE if enabled else ProviderStatus.INACTIVE


def _row(provider: Provider) -> SourceRow:
    base_url = provider.base_url.value if provider.base_url else ""
    enabled = provider.status == ProviderStatus.ACTIVE
    return SourceRow(provider.slug, provider.parser or "", enabled, base_url, provider.api_key, provider.id)


def _search_sources():
    return Provider.filter(parser__isnull=False).select_related("base_url")


class SourceDatabaseRepo(SourceRepo):
    async def list_all(self) -> list[SourceRow]:
        return [_row(provider) for provider in await _search_sources().order_by("created_at")]

    async def get(self, id: uuid.UUID) -> SourceRow | None:
        provider = await _search_sources().get_or_none(id=id)
        return _row(provider) if provider else None

    async def create(self, name: str, kind: str, base_url: str, api_key: str | None, enabled: bool) -> SourceRow:
        provider = await Provider.create(
            name=name,
            slug=name,
            parser=kind,
            base_url=await url_row(base_url),
            api_key=api_key,
            status=_status(enabled),
        )
        return _row(provider)

    async def insert_missing(self, rows: Sequence[SourceRow]) -> int:
        # Every provider's slug, not only search sources': the slug is unique across them all.
        stored = set(await Provider.all().values_list("slug", flat=True))
        fresh = [row for row in rows if row.name not in stored]
        if fresh:
            # ignore_conflicts: two processes seeding at once must not fail on the unique slug.
            await Provider.bulk_create(
                [
                    Provider(
                        name=row.name,
                        slug=row.name,
                        parser=row.kind,
                        base_url=await url_row(row.base_url),
                        api_key=row.api_key,
                        status=_status(row.enabled),
                    )
                    for row in fresh
                ],
                ignore_conflicts=True,
            )
        return len(fresh)

    async def update(
        self,
        id: uuid.UUID,
        enabled: bool | None,
        base_url: str | None,
        api_key: str | None = None,
        clear_api_key: bool = False,
    ) -> SourceRow | None:
        provider = await _search_sources().get_or_none(id=id)
        if provider is None:
            return None
        if enabled is not None:
            provider.status = _status(enabled)
        if base_url is not None:
            provider.base_url = await url_row(base_url)
        if clear_api_key:
            provider.api_key = None
        elif api_key is not None:
            provider.api_key = api_key
        await provider.save()
        return _row(provider)

    async def delete(self, id: uuid.UUID) -> bool:
        provider = await Provider.get_or_none(id=id, parser__isnull=False)
        if provider is None:
            return False
        await provider.delete()
        return True
