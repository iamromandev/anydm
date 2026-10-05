"""Every search source as one list: add one, change one, put one back, delete one, and ask one whether it answers."""

import time
import uuid
from collections.abc import Sequence

import httpx

from src.data.repo.search.interface.source import SourceRepo, SourceRow
from src.data.schema.catalog import SourceListSchema, SourceSchema, SourceTestSchema
from src.lib.sources.registry import (
    BUILTINS,
    KINDS,
    default_url,
    make_source,
    test_query_for,
    valid_name,
    validate_base_url,
)
from src.lib.sources.source import Source
from src.service.source import error as source_error
from src.service.source.failure import failure_message


def _masked(api_key: str | None) -> str | None:
    """What a client may see of a key: nothing, asterisks for a short one, or first four, an ellipsis, last four."""
    if api_key is None:
        return None
    if len(api_key) <= 8:
        return "********"
    return f"{api_key[:4]}…{api_key[-4:]}"


def _view(row: SourceRow) -> SourceSchema:
    """A stored row as a client sees it; every view comes from a stored row, so the id is always set."""
    assert row.id is not None
    return SourceSchema(
        id=row.id,
        name=row.name,
        kind=row.kind,
        enabled=row.enabled,
        base_url=row.base_url,
        api_key_masked=_masked(row.api_key),
        deletable=row.name not in BUILTINS,
        default_url=default_url(row.kind),
    )


def _checked(address: str) -> str:
    try:
        return validate_base_url(address)
    except ValueError as error:
        raise source_error.invalid_address(str(error)) from error


def _ms(started: float) -> int:
    return round((time.monotonic() - started) * 1000)


class SourceService:
    def __init__(self, repo: SourceRepo, client: httpx.AsyncClient, timeout_s: float) -> None:
        self._repo = repo
        self._client = client
        self._timeout = timeout_s

    async def list(self) -> SourceListSchema:
        rows = await self._repo.list_all()
        by_name = {row.name: row for row in rows}
        ordered = [by_name[name] for name in BUILTINS if name in by_name]
        ordered += [row for row in rows if row.name not in BUILTINS]
        return SourceListSchema(sources=[_view(row) for row in ordered])

    async def create(self, name: str, kind: str, base_url: str, api_key: str | None, enabled: bool) -> SourceSchema:
        if not valid_name(name):
            raise source_error.invalid_value("A source name is lowercase letters, digits and dashes, up to 64 characters")
        if kind not in KINDS:
            raise source_error.invalid_value(f"Unknown source kind: {kind}")
        address = _checked(base_url)
        if api_key is not None and kind != "torznab":
            raise source_error.invalid_value("An API key only means anything for a Torznab indexer")
        if any(row.name == name for row in await self._repo.list_all()):
            raise source_error.invalid_value(f"There is already a source named {name}")
        return _view(await self._repo.create(name, kind, address, api_key or None, enabled))

    async def update(self, id: uuid.UUID, enabled: bool | None, base_url: str | None, api_key: str | None) -> SourceSchema:
        row = await self._repo.get(id)
        if row is None:
            raise source_error.source_not_found(id)
        address = _checked(base_url) if base_url is not None else None
        if api_key is not None and row.kind != "torznab":
            raise source_error.invalid_value("An API key only means anything for a Torznab indexer")
        updated = await self._repo.update(id, enabled, address, api_key or None, clear_api_key=api_key == "")
        assert updated is not None  # the row existed a moment ago
        return _view(updated)

    async def delete(self, id: uuid.UUID) -> None:
        row = await self._repo.get(id)
        if row is None:
            raise source_error.source_not_found(id)
        if row.name in BUILTINS:
            raise source_error.not_deletable(row.name)
        await self._repo.delete(id)

    async def reset(self, id: uuid.UUID) -> SourceSchema:
        row = await self._repo.get(id)
        if row is None:
            raise source_error.source_not_found(id)
        builtin = BUILTINS.get(row.kind)
        if builtin is None:
            raise source_error.no_default(row.name)
        updated = await self._repo.update(id, builtin.default_enabled, builtin.default_url)
        assert updated is not None  # the row existed a moment ago
        return _view(updated)

    async def test(self, id: uuid.UUID, base_url: str | None, api_key: str | None) -> SourceTestSchema:
        row = await self._repo.get(id)
        if row is None:
            raise source_error.source_not_found(id)
        address = _checked(base_url) if base_url else row.base_url
        key = api_key if api_key is not None else row.api_key
        return await self._ask(row.kind, make_source(row.kind, row.name, address, key))

    async def probe(self, kind: str, base_url: str, api_key: str | None) -> SourceTestSchema:
        if kind not in KINDS:
            raise source_error.invalid_value(f"Unknown source kind: {kind}")
        if api_key is not None and kind != "torznab":
            raise source_error.invalid_value("An API key only means anything for a Torznab indexer")
        return await self._ask(kind, make_source(kind, kind, _checked(base_url), api_key or None))

    async def _ask(self, kind: str, source: Source) -> SourceTestSchema:
        started = time.monotonic()
        try:
            results = await source.fetch(self._client, test_query_for(kind), "all", self._timeout)
        except Exception as error:
            message = failure_message(error, self._timeout)
            if message is None:
                raise
            return SourceTestSchema(ok=False, took_ms=_ms(started), message=message)
        return SourceTestSchema(ok=True, count=len(results), took_ms=_ms(started), message="Answered")

    async def enabled_sources(self) -> Sequence[Source]:
        """The sources a request should ask, built at their stored addresses; read on every request."""
        return [
            make_source(row.kind, row.name, row.base_url, row.api_key)
            for row in await self._repo.list_all()
            if row.enabled
        ]
