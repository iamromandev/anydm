from __future__ import annotations

from collections.abc import Sequence

from src.data.db.model import SearchSource
from src.data.repo.search.interface.source import SearchSourceRepo, SearchSourceRow


def _row(source: SearchSource) -> SearchSourceRow:
    return SearchSourceRow(source.name, source.enabled, source.base_url)


class SearchSourceDatabaseRepo(SearchSourceRepo):
    async def list_all(self) -> list[SearchSourceRow]:
        return [_row(source) for source in await SearchSource.all().order_by("created_at")]

    async def get(self, name: str) -> SearchSourceRow | None:
        source = await SearchSource.get_or_none(name=name)
        return _row(source) if source else None

    async def insert_missing(self, rows: Sequence[SearchSourceRow]) -> int:
        stored = {source.name for source in await SearchSource.all()}
        fresh = [row for row in rows if row.name not in stored]
        if fresh:
            # ignore_conflicts: two processes seeding at once must not fail on the unique name.
            await SearchSource.bulk_create(
                [SearchSource(name=row.name, enabled=row.enabled, base_url=row.base_url) for row in fresh],
                ignore_conflicts=True,
            )
        return len(fresh)

    async def update(self, name: str, enabled: bool | None, base_url: str | None) -> SearchSourceRow | None:
        source = await SearchSource.get_or_none(name=name)
        if source is None:
            return None
        if enabled is not None:
            source.enabled = enabled
        if base_url is not None:
            source.base_url = base_url
        await source.save()
        return _row(source)
