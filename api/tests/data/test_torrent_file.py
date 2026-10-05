"""TorrentFile: one file inside a torrent, its path and its size."""

from src.data.db.model.torrent.torrent import TorrentFile
from tortoise.fields.relational import ForeignKeyFieldInstance


def test_torrent_file_has_the_schema_columns() -> None:
    names = set(TorrentFile._meta.db_fields)
    assert {"path", "size", "created_at", "updated_at"} <= names
    assert "torrent" in TorrentFile._meta.fk_fields
    assert "deleted_at" not in names


def test_torrent_file_torrent_is_a_cascade_fk() -> None:
    field = TorrentFile._meta.fields_map["torrent"]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert field.null is False
    assert field.model_name == "model.Torrent"
    assert field.related_name == "files"
    assert getattr(field, "on_delete", None) == "CASCADE"


def test_torrent_file_columns() -> None:
    from tortoise import fields

    fields_map = TorrentFile._meta.fields_map
    assert isinstance(fields_map["path"], fields.TextField)
    assert fields_map["path"].null is False
    assert isinstance(fields_map["size"], fields.BigIntField)
    assert fields_map["size"].null is False


def test_torrent_file_meta_is_transfer() -> None:
    assert TorrentFile.Meta.table == "torrent_file"
    assert TorrentFile.Meta.schema == "transfer"
