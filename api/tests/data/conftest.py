"""The in-memory SQLite database the model tests share.

SQLite has no schemas, and Tortoise qualifies every query with ``Meta.schema``
(``"iam"."user"``), so a model that names one cannot be read back from SQLite.
The schema is cleared before Tortoise reads the models and put back afterwards,
so the tests see the same columns, keys and constraints and only the schema
name differs. What a schema itself does (the tables landing in it, the hand
written indexes and the view) is covered against Postgres in
``tests/integration/test_schema.py``.
"""

from collections.abc import AsyncIterator

import pytest_asyncio
from src.data.db import model as models
from tortoise import Tortoise
from tortoise.models import Model


def _model_classes() -> list[type[Model]]:
    return [
        value
        for value in vars(models).values()
        if isinstance(value, type) and issubclass(value, Model) and value is not Model
    ]


@pytest_asyncio.fixture
async def sqlite() -> AsyncIterator[None]:
    classes = _model_classes()
    schemas = {cls: cls._meta.schema for cls in classes}
    for cls in classes:
        cls._meta.schema = None
    try:
        await Tortoise.init(db_url="sqlite://:memory:", modules={"model": ["src.data.db.model"]})
        await Tortoise.generate_schemas()
        yield
    finally:
        await Tortoise.close_connections()
        for cls, schema in schemas.items():
            cls._meta.schema = schema
