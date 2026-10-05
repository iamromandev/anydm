"""The Source table: a URL used one way, and how it is read: DIRECT, CONTENT, or TORRENT."""

from src.data.db.model.catalog.source import Source
from src.data.type import SourceKind
from tortoise.fields.relational import ForeignKeyFieldInstance


def test_source_has_the_schema_columns() -> None:
    names = set(Source._meta.db_fields)
    assert {"kind", "created_at", "updated_at"} <= names
    assert "deleted_at" not in names
    # FKs live outside ``db_fields`` in this Tortoise version; ``url`` carries ``url_id``.
    assert "url" in Source._meta.fk_fields


def test_source_kind_defaults_to_direct() -> None:
    field = Source._meta.fields_map["kind"]
    assert field.null is False
    assert field.default == SourceKind.DIRECT


def test_source_url_is_a_required_fk_to_url() -> None:
    field = Source._meta.fields_map["url"]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert field.null is False
    assert field.model_name == "model.Url"
    assert field.related_name == "sources"
    assert getattr(field, "on_delete", None) == "RESTRICT"


def test_source_provider_is_a_required_fk_to_provider() -> None:
    field = Source._meta.fields_map["provider"]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert field.null is False
    assert field.model_name == "model.Provider"
    assert field.related_name == "sources"
    assert getattr(field, "on_delete", None) == "CASCADE"


def test_source_db_fields_cover_the_schema() -> None:
    names = set(Source._meta.db_fields)
    assert {"kind", "created_at", "updated_at"} <= names
    assert "url" in Source._meta.fk_fields
    assert "provider" in Source._meta.fk_fields


def test_source_kind_is_the_three_ways() -> None:
    assert (SourceKind.DIRECT, SourceKind.CONTENT, SourceKind.TORRENT) == ("direct", "content", "torrent")


def test_source_schema_is_catalog() -> None:
    assert Source.Meta.schema == "catalog"


def test_source_url_provider_kind_is_unique() -> None:
    assert {frozenset(cols) for cols in Source.Meta.unique_together} >= {frozenset({"url", "provider", "kind"})}
