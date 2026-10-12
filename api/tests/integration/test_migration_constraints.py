"""Everything a model declares must exist in a freshly migrated database.

``makemigrations`` writes a model's declarations into the migration, and a regenerated chain can lose
them without anything failing: the migration state agrees with the models, so it stays quiet, and the
index simply is not there. A ``Meta.constraints`` entry written into ``CreateModel``'s options emits no
DDL; only an ``AddConstraint`` does. (The auth service of Exateks lost fifty-one that way, twice.)

So this compares what the models declare with what Postgres has after the migrations ran, rather than
checking the mechanism: unique-together sets, named indexes, named constraints and unique columns.
"""

from __future__ import annotations

import pytest
from src.data.db import model as model_package
from tortoise import Model, Tortoise
from tortoise.indexes import Index

pytestmark = [pytest.mark.integration, pytest.mark.usefixtures("db")]

_INDEXES = """
SELECT n.nspname AS schema, t.relname AS table, i.relname AS name, ix.indisunique AS is_unique,
       array_agg(a.attname ORDER BY k.ord) AS columns
FROM pg_index ix
JOIN pg_class t ON t.oid = ix.indrelid
JOIN pg_class i ON i.oid = ix.indexrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
GROUP BY n.nspname, t.relname, i.relname, ix.indisunique
"""


def _models() -> list[type[Model]]:
    return [m for m in vars(model_package).values() if isinstance(m, type) and issubclass(m, Model)]


def _table(model: type[Model]) -> tuple[str, str]:
    return model._meta.schema or "", model._meta.db_table


async def _database() -> list[dict]:
    return await Tortoise.get_connection("default").execute_query_dict(_INDEXES)


def _column(model: type[Model], name: str) -> str:
    """The database column of a field; a relation's is its ``<name>_id``."""
    meta = model._meta
    source = getattr(meta.fields_map[name], "source_field", None) or name
    return meta.fields_db_projection.get(source, source)


def _columns(model: type[Model], names: tuple[str, ...]) -> frozenset[str]:
    return frozenset(_column(model, name) for name in names)


@pytest.mark.asyncio
async def test_every_declared_unique_set_is_a_unique_index() -> None:
    found = {(row["schema"], row["table"], frozenset(row["columns"])) for row in await _database() if row["is_unique"]}
    missing = []
    for model in _models():
        schema, table = _table(model)
        for names in model._meta.unique_together:
            if (schema, table, _columns(model, tuple(names))) not in found:
                missing.append(f"{model.__name__}{tuple(names)}")
    assert not missing, f"declared unique together, but the database has no such unique index: {missing}"


@pytest.mark.asyncio
async def test_every_unique_column_is_a_unique_index() -> None:
    found = {(row["schema"], row["table"], frozenset(row["columns"])) for row in await _database() if row["is_unique"]}
    missing = []
    for model in _models():
        schema, table = _table(model)
        for name, field in model._meta.fields_map.items():
            unique = getattr(field, "unique", False) and not field.pk and name in model._meta.fields_db_projection
            if unique and (schema, table, _columns(model, (name,))) not in found:
                missing.append(f"{model.__name__}.{name}")
    assert not missing, f"declared unique, but the database has no unique index on them: {missing}"


@pytest.mark.asyncio
async def test_every_named_index_and_constraint_exists_with_its_columns() -> None:
    by_name = {(row["schema"], row["name"]): row for row in await _database()}
    wrong = []
    for model in _models():
        schema, table = _table(model)
        for index in model._meta.indexes:
            name = getattr(index, "name", None)
            if not isinstance(index, Index) or not name:
                continue
            row = by_name.get((schema, name))
            if row is None or row["table"] != table:
                wrong.append(f"{model.__name__}: index {name} is missing")
            elif index.fields and set(row["columns"]) != _columns(model, tuple(index.fields)):
                wrong.append(f"{model.__name__}: index {name} covers {row['columns']}")
        for constraint in getattr(model._meta, "constraints", ()):
            name = getattr(constraint, "name", None)
            if name and (schema, name) not in by_name:
                wrong.append(f"{model.__name__}: constraint {name} is missing")
    assert not wrong, wrong
