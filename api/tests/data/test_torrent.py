"""Torrent: one source's torrent, named, hashed, and measured."""

from src.data.db.model.torrent.torrent import Torrent
from tortoise.fields import CharField
from tortoise.fields.relational import ForeignKeyFieldInstance


def test_torrent_has_the_schema_columns() -> None:
    names = set(Torrent._meta.db_fields)
    assert {"name", "info_hash", "total_bytes", "created_at", "updated_at"} <= names
    assert "source" in Torrent._meta.fk_fields
    assert "deleted_at" not in names


def test_torrent_source_is_a_cascade_fk() -> None:
    field = Torrent._meta.fields_map["source"]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert field.null is False
    assert field.model_name == "model.Source"
    assert field.related_name == "torrents"
    assert getattr(field, "on_delete", None) == "CASCADE"


def test_torrent_info_hash_is_unique_varchar_64() -> None:
    field = Torrent._meta.fields_map["info_hash"]
    assert isinstance(field, CharField)
    assert field.max_length == 64
    assert field.unique is True
    assert field.null is False


def test_torrent_columns() -> None:
    from tortoise import fields

    fields_map = Torrent._meta.fields_map
    assert isinstance(fields_map["name"], fields.CharField)
    assert isinstance(fields_map["total_bytes"], fields.BigIntField)
    assert fields_map["total_bytes"].null is True


def test_torrent_meta_is_torrent() -> None:
    assert Torrent.Meta.table == "torrent"
    assert Torrent.Meta.schema == "torrent"
