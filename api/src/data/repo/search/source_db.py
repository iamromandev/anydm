from __future__ import annotations

import uuid
from collections.abc import Sequence

from src.data.db.model import SearchSource
from src.data.repo.search.interface.source import SearchSourceRepo, SearchSourceRow


def _row(source: SearchSource) -> SearchSourceRow:
    return SearchSourceRow(source.name, source.kind, source.enabled, source.base_url, source.api_key, source.id)


class SearchSourceDatabaseRepo(SearchSourceRepo):
    async def list_all(self) -> list[SearchSourceRow]:
        return [_row(source) for source in await SearchSource.all().order_by("created_at")]

    async def get(self, id: uuid.UUID) -> SearchSourceRow | None:
        source = await SearchSource.get_or_none(id=id)
        return _row(source) if source else None

    async def create(self, name: str, kind: str, base_url: str, api_key: str | None, enabled: bool) -> SearchSourceRow:
        return _row(await SearchSource.create(name=name, kind=kind, base_url=base_url, api_key=api_key, enabled=enabled))

    async def insert_missing(self, rows: Sequence[SearchSourceRow]) -> int:
        stored = {source.name for source in await SearchSource.all()}
        fresh = [row for row in rows if row.name not in stored]
        if fresh:
            # ignore_conflicts: two processes seeding at once must not fail on the unique name.
            await SearchSource.bulk_create(
                [
                    SearchSource(name=row.name, kind=row.kind, enabled=row.enabled, base_url=row.base_url, api_key=row.api_key)
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
    ) -> SearchSourceRow | None:
        source = await SearchSource.get_or_none(id=id)
        if source is None:
            return None
        if enabled is not None:
            source.enabled = enabled
        if base_url is not None:
            source.base_url = base_url
        if clear_api_key:
            source.api_key = None
        elif api_key is not None:
            source.api_key = api_key
        await source.save()
        return _row(source)

    async def delete(self, id: uuid.UUID) -> bool:
        source = await SearchSource.get_or_none(id=id)
        if source is None:
            return False
        await source.delete()
        return True
