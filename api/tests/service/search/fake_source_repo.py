"""An in-memory SearchSourceRepo, for tests that don't need Postgres."""

from collections.abc import Sequence
from dataclasses import replace

from src.data.repo.search.interface.source import SearchSourceRepo, SearchSourceRow


class FakeSourceRepo(SearchSourceRepo):
    def __init__(self, rows: Sequence[SearchSourceRow] = ()) -> None:
        self.rows: dict[str, SearchSourceRow] = {row.name: row for row in rows}

    async def list_all(self) -> list[SearchSourceRow]:
        return list(self.rows.values())

    async def get(self, name: str) -> SearchSourceRow | None:
        return self.rows.get(name)

    async def insert_missing(self, rows: Sequence[SearchSourceRow]) -> int:
        added = 0
        for row in rows:
            if row.name not in self.rows:
                self.rows[row.name] = row
                added += 1
        return added

    async def update(self, name: str, enabled: bool | None, base_url: str | None) -> SearchSourceRow | None:
        row = self.rows.get(name)
        if row is None:
            return None
        row = replace(
            row,
            enabled=row.enabled if enabled is None else enabled,
            base_url=row.base_url if base_url is None else base_url,
        )
        self.rows[name] = row
        return row
