"""File: a file belonging to one download, with its on-disk name/path and size."""

from src.data.db.model.transfer.file import File
from tortoise.fields.relational import ForeignKeyFieldInstance


def test_file_has_the_schema_columns() -> None:
    names = set(File._meta.db_fields)
    assert {"filename", "path", "size", "created_at", "updated_at"} <= names
    assert "download" in File._meta.fk_fields
    assert "deleted_at" not in names


def test_file_download_is_a_cascade_fk() -> None:
    field = File._meta.fields_map["download"]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert field.null is False
    assert field.model_name == "model.Download"
    assert field.related_name == "files"
    assert getattr(field, "on_delete", None) == "CASCADE"


def test_file_filename_and_path_and_size() -> None:
    fields_map = File._meta.fields_map
    assert fields_map["filename"].max_length == 512
    assert fields_map["filename"].null is False
    from tortoise import fields

    assert isinstance(fields_map["path"], fields.TextField)
    assert fields_map["path"].null is False
    assert isinstance(fields_map["size"], fields.BigIntField)
    assert fields_map["size"].null is True


def test_file_meta_is_file_table_in_transfer() -> None:
    assert File.Meta.table == "file"
    assert File.Meta.schema == "transfer"
