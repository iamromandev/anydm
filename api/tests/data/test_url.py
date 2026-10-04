"""The Url table: the address asked at, split into value/normalized and its parts."""

from src.data.db.model.shared.url import Url
from tortoise import fields


def test_url_has_the_schema_columns() -> None:
    names = set(Url._meta.db_fields)
    assert {
        "value",
        "normalized",
        "scheme",
        "host",
        "port",
        "path",
        "query",
        "fragment",
        "created_at",
        "updated_at",
    } <= names


def test_url_drops_the_old_columns() -> None:
    names = set(Url._meta.db_fields)
    assert "kind" not in names
    assert "url" not in names
    assert "deleted_at" not in names


def test_url_required_vs_nullable() -> None:
    fields_map = Url._meta.fields_map
    assert fields_map["value"].null is False
    assert fields_map["normalized"].null is False
    assert fields_map["scheme"].null is False
    for name in ("host", "port", "path", "query", "fragment"):
        assert fields_map[name].null is True, name


def test_url_column_types() -> None:
    fields_map = Url._meta.fields_map
    assert isinstance(fields_map["value"], fields.TextField)
    assert isinstance(fields_map["normalized"], fields.TextField)
    assert isinstance(fields_map["scheme"], fields.CharField)
    assert fields_map["scheme"].max_length == 16
    assert isinstance(fields_map["host"], fields.CharField)
    assert fields_map["host"].max_length == 255
    assert isinstance(fields_map["port"], fields.SmallIntField)
    for name in ("path", "query", "fragment"):
        assert isinstance(fields_map[name], fields.TextField), name


def test_url_str_shows_the_value() -> None:
    assert str(Url(value="https://example.com")) == "[Url: https://example.com]"
