"""Torrent: content identified by its info hash, named and measured; sources lead to it."""

from src.data.db.model.catalog.source import Source
from src.data.db.model.torrent.torrent import Torrent
from tortoise.fields import CharField
from tortoise.fields.relational import ForeignKeyFieldInstance


def test_torrent_has_the_schema_columns() -> None:
    names = set(Torrent._meta.db_fields)
    assert {"name", "info_hash", "total_size", "created_at", "updated_at"} <= names
    assert "source" not in Torrent._meta.fields_map
    assert "deleted_at" not in names


def test_a_source_leads_to_a_torrent_and_several_can_lead_to_one() -> None:
    field = Source._meta.fields_map["torrent"]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert field.null is True
    assert field.model_name == "model.Torrent"
    assert field.related_name == "sources"
    assert getattr(field, "on_delete", None) == "SET NULL"


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
    assert isinstance(fields_map["total_size"], fields.BigIntField)
    assert fields_map["total_size"].null is True


def test_torrent_meta_is_torrent() -> None:
    assert Torrent.Meta.table == "torrent"
    assert Torrent.Meta.schema == "torrent"
