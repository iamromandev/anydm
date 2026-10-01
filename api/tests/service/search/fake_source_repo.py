"""An in-memory SearchSourceRepo, for tests that don't need Postgres."""

import uuid
from collections.abc import Sequence
from dataclasses import replace

from src.data.repo.search.interface.source import SearchSourceRepo, SearchSourceRow


class FakeSourceRepo(SearchSourceRepo):
    def __init__(self, rows: Sequence[SearchSourceRow] = ()) -> None:
        self.rows: dict[uuid.UUID, SearchSourceRow] = {}
        for row in rows:
            key = row.id or uuid.uuid4()
            self.rows[key] = replace(row, id=key)

    def _by_name(self, name: str) -> SearchSourceRow | None:
        return next((row for row in self.rows.values() if row.name == name), None)

    async def list_all(self) -> list[SearchSourceRow]:
        return list(self.rows.values())

    async def get(self, id: uuid.UUID) -> SearchSourceRow | None:
        return self.rows.get(id)

    async def create(self, name: str, kind: str, base_url: str, api_key: str | None, enabled: bool) -> SearchSourceRow:
        key = uuid.uuid4()
        row = SearchSourceRow(name, kind, enabled, base_url, api_key, key)
        self.rows[key] = row
        return row

    async def insert_missing(self, rows: Sequence[SearchSourceRow]) -> int:
        added = 0
        for row in rows:
            if self._by_name(row.name) is None:
                key = row.id or uuid.uuid4()
                self.rows[key] = replace(row, id=key)
                added += 1
        return added

    async def update(
        self,
        id: uuid.UUID,
        enabled: bool | None,
        base_url: str | None,
        api_key: str | None = None,
        clear_api_key: bool = False,
    ) -> SearchSourceRow | None:
        row = self.rows.get(id)
        if row is None:
            return None
        row = replace(
            row,
            enabled=row.enabled if enabled is None else enabled,
            base_url=row.base_url if base_url is None else base_url,
            api_key=None if clear_api_key else (api_key if api_key is not None else row.api_key),
        )
        self.rows[id] = row
        return row

    async def delete(self, id: uuid.UUID) -> bool:
        return self.rows.pop(id, None) is not None
