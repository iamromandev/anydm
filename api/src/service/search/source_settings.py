"""Managing the built-in sources: list them, change one, put one back, and ask one whether it answers."""

import time
import uuid

import httpx

from src.data.repo.search.interface.source import SearchSourceRepo, SearchSourceRow
from src.data.schema.search import BuiltinSourceSchema, BuiltinSourcesSchema, BuiltinTestSchema
from src.lib.sources.registry import BUILTINS, Builtin, validate_base_url
from src.lib.sources.source import Source
from src.service.search import error as search_error
from src.service.search.failure import failure_message


def _known(name: str) -> Builtin:
    builtin = BUILTINS.get(name)
    if builtin is None:
        raise search_error.source_not_found(name)
    return builtin


def _checked(address: str) -> str:
    try:
        return validate_base_url(address)
    except ValueError as error:
        raise search_error.invalid_address(str(error)) from error


def _view(builtin: Builtin, row: SearchSourceRow | None) -> BuiltinSourceSchema:
    """The source as a client sees it; with no row yet, the constants."""
    return BuiltinSourceSchema(
        name=builtin.name,
        label=builtin.label,
        enabled=row.enabled if row else builtin.default_enabled,
        base_url=row.base_url if row else builtin.default_url,
        default_url=builtin.default_url,
    )


def _default(builtin: Builtin) -> SearchSourceRow:
    return SearchSourceRow(builtin.name, builtin.name, builtin.default_enabled, builtin.default_url, None)


def _ms(started: float) -> int:
    return round((time.monotonic() - started) * 1000)


class SourceSettingsService:
    def __init__(self, repo: SearchSourceRepo, client: httpx.AsyncClient, timeout_s: float) -> None:
        self._repo = repo
        self._client = client
        self._timeout = timeout_s

    async def list_sources(self) -> BuiltinSourcesSchema:
        rows = {row.name: row for row in await self._repo.list_all()}
        return BuiltinSourcesSchema(sources=[_view(b, rows.get(b.name)) for b in BUILTINS.values()])

    async def _id(self, name: str) -> uuid.UUID | None:
        # Task 2 replaces this whole service with an id-keyed one; until then the name is resolved here.
        rows = await self._repo.list_all()
        return next((row.id for row in rows if row.name == name and row.id is not None), None)

    async def update(self, name: str, enabled: bool | None, base_url: str | None) -> BuiltinSourceSchema:
        builtin = _known(name)
        address = _checked(base_url) if base_url is not None else None
        await self._repo.insert_missing([_default(builtin)])
        row_id = await self._id(name)
        if row_id is None:  # insert_missing just ensured the row; unreachable
            raise search_error.source_not_found(name)
        return _view(builtin, await self._repo.update(row_id, enabled, address))

    async def reset(self, name: str) -> BuiltinSourceSchema:
        builtin = _known(name)
        await self._repo.insert_missing([_default(builtin)])
        row_id = await self._id(name)
        if row_id is None:  # insert_missing just ensured the row; unreachable
            raise search_error.source_not_found(name)
        return _view(builtin, await self._repo.update(row_id, builtin.default_enabled, builtin.default_url))

    async def test(self, name: str, base_url: str | None) -> BuiltinTestSchema:
        builtin = _known(name)
        if base_url:
            address = _checked(base_url)
        else:
            row_id = await self._id(name)
            row = await self._repo.get(row_id) if row_id else None
            address = row.base_url if row else builtin.default_url
        started = time.monotonic()
        try:
            results = await builtin.make(address).fetch(self._client, builtin.test_query, "all", self._timeout)
        except Exception as error:
            message = failure_message(error, self._timeout)
            if message is None:
                raise
            return BuiltinTestSchema(ok=False, took_ms=_ms(started), message=message)
        return BuiltinTestSchema(ok=True, count=len(results), took_ms=_ms(started), message="Answered")

    async def enabled_sources(self) -> list[Source]:
        """The built-ins a request should ask, at their stored addresses; a source with no row yet uses the constants."""
        rows = {row.name: row for row in await self._repo.list_all()}
        sources: list[Source] = []
        for builtin in BUILTINS.values():
            row = rows.get(builtin.name)
            if row.enabled if row else builtin.default_enabled:
                sources.append(builtin.make(row.base_url if row else builtin.default_url))
        return sources
